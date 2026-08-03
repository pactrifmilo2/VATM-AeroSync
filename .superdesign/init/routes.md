# Routes and Navigation

This is a WinUI 3 desktop application; navigation is tag-based rather than URL-based.

Router source: `aerosync-ui/MainWindow.xaml.cs`

| Navigation tag | Page | Current implementation |
| --- | --- | --- |
| `dashboard` | `Views/DashboardPage.xaml` | Monitoring dashboard and job details |
| `config` | `Views/ConfigPage.xaml` | Runtime settings |
| `overview` | `Views/PlaceholderPage.xaml` | Placeholder |
| `history` | `Views/PlaceholderPage.xaml` | Placeholder |
| `sources` | `Views/PlaceholderPage.xaml` | Placeholder |
| `reports` | `Views/PlaceholderPage.xaml` | Placeholder |
| `alerts` | `Views/PlaceholderPage.xaml` | Placeholder |
| `system` | `Views/PlaceholderPage.xaml` | Placeholder |
| proposed `email-resend` | new `Views/EmailResendPage.xaml` | Filter and resend one complete email |

```csharp
private void Navigate(string tag)
{
    ContentFrame.Content = tag switch
    {
        "config" => new ConfigPage(apiClient),
        "dashboard" => new DashboardPage(apiClient),
        _ => new PlaceholderPage((RootNav.SelectedItem as NavigationViewItem)?.Content?.ToString() ?? "AeroSync")
    };
}
```
