# Page Dependency Trees

## Monitoring dashboard

Entry: `aerosync-ui/Views/DashboardPage.xaml`

Dependencies:
- `aerosync-ui/Views/DashboardPage.xaml.cs`
  - `aerosync-ui/ViewModels/DashboardViewModel.cs`
    - `aerosync-ui/Models/ApiModels.cs`
    - `aerosync-ui/Services/AeroSyncApiClient.cs`
  - `aerosync-ui/Views/JobDetailsDialog.xaml`
    - `aerosync-ui/Views/JobDetailsDialog.xaml.cs`
    - `aerosync-ui/ViewModels/JobDetailsViewModel.cs`
- `aerosync-ui/MainWindow.xaml`
  - `aerosync-ui/MainWindow.xaml.cs`
- `aerosync-ui/App.xaml`

## Runtime configuration

Entry: `aerosync-ui/Views/ConfigPage.xaml`

Dependencies:
- `aerosync-ui/Views/ConfigPage.xaml.cs`
  - `aerosync-ui/ViewModels/ConfigViewModel.cs`
    - `aerosync-ui/Models/ApiModels.cs`
    - `aerosync-ui/Services/AeroSyncApiClient.cs`
- `aerosync-ui/MainWindow.xaml`
  - `aerosync-ui/MainWindow.xaml.cs`
- `aerosync-ui/App.xaml`

## Proposed email resend page

New target: `aerosync-ui/Views/EmailResendPage.xaml`

Expected dependencies:
- new `aerosync-ui/Views/EmailResendPage.xaml.cs`
- new `aerosync-ui/ViewModels/EmailResendViewModel.cs`
- new `aerosync-ui/Views/EmailResendDialog.xaml`
- `aerosync-ui/Models/ApiModels.cs`
- `aerosync-ui/Services/AeroSyncApiClient.cs`
- `aerosync-ui/MainWindow.xaml`
- `aerosync-ui/App.xaml`

Closest style anchor: `aerosync-ui/Views/DashboardPage.xaml`.
