package com.haoforyou.service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.haoforyou.dto.MailData;
import com.haoforyou.dto.MessageSummary;
import com.haoforyou.dto.GoogleApiRequest;
import com.haoforyou.model.GoogleToken;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MailService {

    private final GoogleAuthService googleAuthService;

    public MailService(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    public MailData query(GoogleToken token, GoogleApiRequest req) throws Exception {
        Gmail gmail = googleAuthService.buildGmailService(token);

        String q = buildQuery(req);
        int limit = req.getLimit() != null ? req.getLimit() : 5;

        ListMessagesResponse listResponse = gmail.users().messages().list("me")
            .setQ(q)
            .setMaxResults((long) limit)
            .execute();

        if (listResponse.getMessages() == null || listResponse.getMessages().isEmpty()) {
            return new MailData(List.of());
        }

        List<MessageSummary> summaries = new ArrayList<>();
        for (Message ref : listResponse.getMessages()) {
            Message msg = gmail.users().messages().get("me", ref.getId())
                .setFormat("metadata")
                .setMetadataHeaders(List.of("Subject", "From", "Date"))
                .execute();
            summaries.add(toSummary(msg));
        }

        return new MailData(summaries);
    }

    private String buildQuery(GoogleApiRequest req) {
        StringBuilder q = new StringBuilder();
        if (hasText(req.getKeyword())) q.append(req.getKeyword()).append(" ");
        if (hasText(req.getFrom())) q.append("from:").append(req.getFrom()).append(" ");
        if (Boolean.TRUE.equals(req.getUnreadOnly())) q.append("is:unread ");
        return q.toString().trim();
    }

    private MessageSummary toSummary(Message msg) {
        String subject = "";
        String from = "";
        String date = "";

        if (msg.getPayload() != null && msg.getPayload().getHeaders() != null) {
            for (MessagePartHeader h : msg.getPayload().getHeaders()) {
                switch (h.getName()) {
                    case "Subject" -> subject = h.getValue();
                    case "From" -> from = h.getValue();
                    case "Date" -> date = h.getValue();
                }
            }
        }

        return new MessageSummary(msg.getId(), subject, from, date,
            msg.getSnippet() != null ? msg.getSnippet() : "");
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
