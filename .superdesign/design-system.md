# VATM AeroSync Desktop Design System

Build for a WinUI 3 desktop administration application at a baseline viewport of 1180×760. Preserve the existing Windows-native control language and left NavigationView shell.

## Visual language

- Use Segoe UI and native WinUI proportions.
- Use `#123A70` for page titles and `#0B4EA2` for branding, section headings, links, and primary actions.
- Use white surfaces with thin `#D6E3F3` borders, 8px corner radius, and no decorative shadow.
- Use 24px page padding, 14px vertical section spacing, and 14–20px card padding.
- Use `#64748B` for helper text and `#334155` for standard body text.
- Status colors: success `#16A34A`, failed `#DC2626`, quarantined `#EA580C`, skipped `#2563EB`, processing `#7C3AED`.
- Tables are dense read-only WinUI DataGrids with explicit headers and visible grid lines.
- Dialogs are wide, clear, and operational; dangerous actions must explain scope and show counts before confirmation.

## Email resend interaction

- Group rows by exact email `messageId`, never sender alone.
- Search/filter by sender email, subject, received time, and overall status.
- Sort email groups by actionable status first, then newest received time.
- Each email row shows sender, subject, received time, attachment count, and compact status counts.
- The Resend button opens a modal containing status checkboxes; at least one is required.
- The modal previews how many files will be replayed, retried, skipped, or blocked.
- Use clear warning language because replay may delete an ATFM permit owned by AEROSYNC before processing again.
- Disable confirmation while no status is selected or any selected job is active.
