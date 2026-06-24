# import json
# from google_auth_oauthlib.flow import InstalledAppFlow
# from google.auth.transport.requests import Request
# from googleapiclient.discovery import build
# from google.oauth2.credentials import Credentials
# from langchain_core.tools import tool
# from zoneinfo import ZoneInfo
# from datetime import datetime, timedelta
# from typing import Annotated, Any, TypedDict
# from app.core.config import config
# from app.schemas.google_service_schema import CreateEventRequest




# def normalize_event(event: dict[str, Any]) -> dict[str, Any]:
#     start = event.get("start", {})
#     end = event.get("end", {})
#     return {
#         "id": event.get("id"),
#         "eventType": event.get("eventType", "default"),
#         "summary": event.get("summary", "(no title)"),
#         "description": event.get("description"),
#         "location": event.get("location"),
#         "start": start.get("dateTime") or start.get("date"),
#         "end": end.get("dateTime") or end.get("date"),
#         "htmlLink": event.get("htmlLink"),
#     }


# def get_calendar_service():
#     creds = None

#     if config.GoogleCalendarConfig.TOKEN_FILE.exists():
#         creds = Credentials.from_authorized_user_file(str(config.GoogleCalendarConfig.TOKEN_FILE), config.GoogleCalendarConfig.SCOPES)
#     print(f"Using Google Calendar token file: {config.GoogleCalendarConfig.TOKEN_FILE}")
#     print(f"Token file exists: {config.GoogleCalendarConfig.TOKEN_FILE.exists()}")
#     print(f"Using Google Calendar credentials file: {config.GoogleCalendarConfig.CREDENTIALS_FILE}")
#     print(f"Credentials file exists: {config.GoogleCalendarConfig.CREDENTIALS_FILE.exists()}")
#     if not creds or not creds.valid:
#         if creds and creds.expired and creds.refresh_token:
#             creds.refresh(Request())
#         else:
#             if not config.GoogleCalendarConfig.CREDENTIALS_FILE.exists():
#                 raise FileNotFoundError(
#                     f"Missing {config.GoogleCalendarConfig.CREDENTIALS_FILE}. Download an OAuth desktop client JSON from Google Cloud Console."
#                 )
#             flow = InstalledAppFlow.from_client_secrets_file(str(config.GoogleCalendarConfig.CREDENTIALS_FILE), config.GoogleCalendarConfig.SCOPES)
#             creds = flow.run_local_server(port=0)

#         config.GoogleCalendarConfig.TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
#         config.GoogleCalendarConfig.TOKEN_FILE.write_text(creds.to_json(), encoding="utf-8")

#     return build("calendar", "v3", credentials=creds)


# def create_event(payload: CreateEventRequest) -> dict[str, Any]:
#     service = get_calendar_service()
#     start_at = payload.start_at
#     if start_at.tzinfo is None:
#         start_at = start_at.replace(tzinfo=ZoneInfo(config.GoogleCalendarConfig.TIMEZONE))
#     end_at = start_at + timedelta(minutes=payload.minutes)

#     event_body = {
#         "eventType": "default",
#         "summary": payload.summary,
#         "description": payload.description,
#         "start": {
#             "dateTime": start_at.isoformat(),
#             "timeZone": config.GoogleCalendarConfig.TIMEZONE,
#         },
#         "end": {
#             "dateTime": end_at.isoformat(),
#             "timeZone": config.GoogleCalendarConfig.TIMEZONE,
#         },
#     }

#     event = service.events().insert(calendarId="primary", body=event_body).execute()
#     return normalize_event(event)


# @tool
# def create_calendar_event(
#     summary: str,
#     start_at: str,
#     minutes: int = 30,
#     description: str | None = None,
# ) -> str:
#     """Create a default Google Calendar event.

#     Args:
#         summary: Event title.
#         start_at: ISO datetime string, for example 2026-05-19T15:00:00+08:00.
#         minutes: Event duration in minutes.
#         description: Optional event description.
#     """
#     event = create_event(
#         CreateEventRequest(
#             summary=summary,
#             start_at=datetime.fromisoformat(start_at),
#             minutes=minutes,
#             description=description,
#         )
#     )
#     return json.dumps({"event": event}, ensure_ascii=False)