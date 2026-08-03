# Shared UI Components

The WinUI 3 client uses Microsoft WinUI controls and CommunityToolkit `DataGrid` directly. It does not currently define custom shared Button, Input, Card, Dialog, Badge, or Table controls.

## Application resources

Source: `aerosync-ui/App.xaml`

This is the shared resource root used by every page.

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

## Repeated visual primitives

- Cards: `Border` with `BorderBrush="#D6E3F3"`, `BorderThickness="1"`, `CornerRadius="8"`, and padding between 14 and 20.
- Page titles: `TextBlock` at 26px, bold, `#123A70`.
- Section titles: `TextBlock` at 18px, bold, `#0B4EA2`.
- Tables: `CommunityToolkit.WinUI.UI.Controls.DataGrid`, read-only, explicit columns, all grid lines.
- Dialogs: WinUI `ContentDialog` with a vertically spaced `StackPanel` body.
