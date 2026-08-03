# Extractable Components

## MainNavigation
- Source: `aerosync-ui/MainWindow.xaml`
- Category: layout
- Description: Left WinUI NavigationView with VATM branding and application destinations.
- Extractable props: `activeItem` (string, default `dashboard`).
- Hardcoded: VATM/HTSLB ADMIN branding, icon names, navigation labels, blue palette, pane layout.

## StatusSummaryCard
- Source: `aerosync-ui/Views/DashboardPage.xaml`
- Category: basic
- Description: Bordered metric card showing a processing status and count.
- Extractable props: `label`, `count`, `statusColor`, `borderColor`.
- Hardcoded: centered stack, 8px radius, 20px padding, 30px count typography.

## FilterPanel
- Source: `aerosync-ui/Views/DashboardPage.xaml`
- Category: basic
- Description: Bordered search/filter section with labeled WinUI inputs.
- Extractable props: `title` and field values.
- Hardcoded: `#D6E3F3` border, 8px radius, 14px padding, blue section title.

## DataTablePanel
- Source: `aerosync-ui/Views/DashboardPage.xaml`
- Category: basic
- Description: Bordered section containing a read-only CommunityToolkit DataGrid.
- Extractable props: `title`, table rows.
- Hardcoded: explicit columns, full grid lines, blue section title.

## ContentDialogShell
- Source: `aerosync-ui/Views/JobDetailsDialog.xaml`
- Category: basic
- Description: Wide WinUI ContentDialog with title, status badge, tables, and action footer.
- Extractable props: `title`, `primaryButtonText`, status and content rows.
- Hardcoded: 780px minimum width, 14px vertical spacing, card borders and typography.
