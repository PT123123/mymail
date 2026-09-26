using Microsoft.UI.Xaml;

namespace WMail.App;

public partial class App : Application
{
    public static Window? MainWindow;

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        MainWindow = new MainWindow();
        MainWindow.Activate();
    }
}
