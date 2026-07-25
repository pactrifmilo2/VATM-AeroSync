# Email Report API

Base path: `/api/reports/emails`

Each report record represents one row in `email_metadata`. In normal processing this
means one row per email attachment. Blocked emails and emails with no attachments
also have a report record.

All timestamps are ISO-8601 local date-time values, for example
`2026-07-24T09:15:00`.

## Interactive documentation

After starting `aerosync-api`, open:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

Swagger UI can execute requests against the locally running API. The OpenAPI JSON
can be imported into Postman or used to generate a frontend API client.

## List report records

```http
GET /api/reports/emails
```

Optional query parameters:

| Parameter | Type | Description |
|---|---|---|
| `from` | date-time | Include records received at or after this value |
| `to` | date-time | Include records received at or before this value |
| `processingStatus` | enum | Email processing status |
| `acknowledgementStatus` | enum | Mailbox acknowledgement status |
| `jobStatus` | enum | Associated sync-job status |
| `sender` | string | Case-insensitive partial sender match |
| `query` | string | Case-insensitive search over message ID, sender, subject, and attachment name |
| `page` | integer | Zero-based page number; defaults to `0` |
| `size` | integer | Page size from `1` to `100`; defaults to `25` |

Records are ordered by `receivedAt` descending and then `id` descending.

Example:

```http
GET /api/reports/emails?from=2026-07-01T00:00:00&processingStatus=FAILED&page=0&size=25
```

```json
{
  "content": [
    {
      "id": 102,
      "syncJobId": 481,
      "permitNumber": "O/F 05199/S/CHK/2026",
      "messageId": "mail-102",
      "sender": "operator@vatm.vn",
      "subject": "Flight permit update",
      "receivedAt": "2026-07-24T09:15:00",
      "attachmentCount": 1,
      "attachmentIndex": 0,
      "attachmentName": "permit.docx",
      "processingStatus": "FAILED",
      "acknowledgementStatus": "MOVED_ERROR",
      "ingestComplete": true,
      "acknowledgedAt": "2026-07-24T09:16:00",
      "jobStatus": "FAILED"
    }
  ],
  "page": 0,
  "size": 25,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false,
  "hasPrevious": false
}
```

The list response intentionally excludes the email body and acknowledgement error.
`permitNumber` contains the normalized permit identity from the associated permit
import, or `null` when the email is not a recognized permit.

## Get report detail

```http
GET /api/reports/emails/{id}
```

The detail response adds the mailbox folder, UID values, email body, and
acknowledgement error. `syncJobId`, `permitNumber`, and `jobStatus` are `null` for
records which did not create a processing job, such as blocked or unsupported
emails. Records with a processing job still have a `null` `permitNumber` until a
permit has been parsed and its import record created.

The frontend must render `body` as untrusted text unless it applies HTML
sanitization.

Unknown IDs return `404` using the API's standard Problem Details response.

## Get report summary

```http
GET /api/reports/emails/summary?from=2026-07-01T00:00:00&to=2026-07-24T23:59:59
```

`from` and `to` are optional. The response returns all enum keys, including
statuses whose count is zero.

```json
{
  "from": "2026-07-01T00:00:00",
  "to": "2026-07-24T23:59:59",
  "totalRecords": 10,
  "processingStatusCounts": {
    "DISCOVERED": 0,
    "DOWNLOADED": 0,
    "PROCESSING": 0,
    "SAVED": 8,
    "FAILED": 2,
    "QUARANTINED": 0,
    "SKIPPED": 0,
    "NO_ATTACHMENT": 0,
    "BLOCKED": 0
  },
  "acknowledgementStatusCounts": {
    "PENDING": 0,
    "MOVED_PROCESSED": 8,
    "MOVED_ERROR": 2,
    "FAILED": 0
  }
}
```

## Validation errors

The API returns `400 Bad Request` when:

- `from` is later than `to`;
- `page` is negative;
- `size` is outside `1` through `100`;
- a timestamp or enum value cannot be parsed.
