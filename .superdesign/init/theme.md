# Theme

## Compact token summary

- Platform: WinUI 3 / Windows App SDK, default light controls via `XamlControlsResources`.
- Primary dark blue: `#123A70` for page titles.
- Primary blue: `#0B4EA2` for branding and section titles.
- Secondary text: `#64748B`; darker secondary: `#526174` and `#334155`.
- Border: `#D6E3F3`; neutral border: `#CBD5E1`.
- Success: `#16A34A` with border `#B7E4C7`.
- Error: `#DC2626` with border `#FECACA`.
- Warning/quarantine: `#EA580C` with border `#FED7AA`.
- Info/skipped: `#2563EB` with border `#BFDBFE`.
- Processing: `#7C3AED` with border `#DDD6FE`.
- Corner radius: 8px cards; 6px compact badges.
- Page padding: 24px; section spacing: 14px; card padding: 14–20px.
- Typography: default WinUI Segoe UI; page title 26px bold; section title 18px bold; metric 30px bold; logs use Consolas.
- Window baseline: 1180×760.
- No custom shadows, dark theme, or breakpoint system is currently defined.

## Raw application resources

Source: `aerosync-ui/App.xaml`

```xml
<Application
    x:Class="AeroSync.UI.App"
    xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
    xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml">
    <Application.Resources>
        <ResourceDictionary>
            <ResourceDictionary.MergedDictionaries>
                <XamlControlsResources xmlns="using:Microsoft.UI.Xaml.Controls" />
            </ResourceDictionary.MergedDictionaries>
        </ResourceDictionary>
    </Application.Resources>
</Application>
```
