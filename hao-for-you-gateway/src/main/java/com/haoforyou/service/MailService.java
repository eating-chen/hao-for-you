package com.haoforyou.service;

import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.haoforyou.dto.MailData;
import com.haoforyou.dto.MessageSummary;
import com.haoforyou.controller.GoogleServiceController;
import com.haoforyou.dto.GoogleMailApiRequest;
import com.haoforyou.model.GoogleToken;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class MailService {

    private final GoogleAuthService googleAuthService;
    private static final Logger log = LoggerFactory.getLogger(MailService.class);


    public MailService(GoogleAuthService googleAuthService) {
        this.googleAuthService = googleAuthService;
    }

    public MailData query(GoogleToken token, GoogleMailApiRequest req) throws Exception {
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
                .setFormat("full")
                .execute();
            summaries.add(toSummary(msg));
        }

        return new MailData(summaries);
    }

    private static final DateTimeFormatter GMAIL_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private String buildQuery(GoogleMailApiRequest req) {
        StringBuilder q = new StringBuilder();
        if (hasText(req.getKeyword())) q.append(req.getKeyword()).append(" ");
        if (hasText(req.getFrom())) q.append("from:").append(req.getFrom()).append(" ");
        if (Boolean.TRUE.equals(req.getUnreadOnly())) q.append("is:unread ");
        appendDateFilter(q, req);
        return q.toString().trim();
    }

    /** Gmail 的 after: 含當天、before: 不含當天，區間結束日要 +1 天才會把 dateTo 當天也含進去 */
    private void appendDateFilter(StringBuilder q, GoogleMailApiRequest req) {
        if (!hasText(req.getDate())) {
            return;
        }

        LocalDate from = LocalDate.parse(req.getDate());
        LocalDate to = hasText(req.getDateTo()) ? LocalDate.parse(req.getDateTo()) : from;

        q.append("after:").append(from.format(GMAIL_DATE_FORMAT)).append(" ");
        q.append("before:").append(to.plusDays(1).format(GMAIL_DATE_FORMAT)).append(" ");
    }

    private MessageSummary toSummary(Message msg) {
        String subject = "";
        String sender = "";
        String date = "";

        if (msg.getPayload() != null && msg.getPayload().getHeaders() != null) {
            for (MessagePartHeader h : msg.getPayload().getHeaders()) {
                switch (h.getName()) {
                    case "Subject" -> subject = h.getValue();
                    case "From" -> sender = h.getValue();
                    case "Date" -> date = h.getValue();
                }
            }
        }

        String snippet = msg.getSnippet() != null ? msg.getSnippet() : "";
        String body = extractBody(msg.getPayload());

        return new MessageSummary(msg.getId(), subject, sender, date, snippet, body);
    }

    private String extractBody(MessagePart part) {
        if (part == null) {
            return "";
        }

        if ("text/plain".equals(part.getMimeType())
                && part.getBody() != null
                && part.getBody().getData() != null) {
            return new String(
                Base64.getUrlDecoder().decode(part.getBody().getData()),
                StandardCharsets.UTF_8
            );
        }

        if (part.getParts() != null) {
            for (MessagePart sub : part.getParts()) {
                String result = extractBody(sub);
                if (!result.isEmpty()) {
                    return result;
                }
            }
        }

        return "";
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
