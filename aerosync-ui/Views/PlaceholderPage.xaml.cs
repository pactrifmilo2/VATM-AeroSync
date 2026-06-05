using Microsoft.UI.Xaml.Controls;

namespace AeroSync.UI.Views;

public sealed partial class PlaceholderPage : Page
{
    public PlaceholderPage(string title)
    {
        InitializeComponent();
        TitleText.Text = title;
    }
}
