using MediaBrowser.Model.Plugins;

namespace EmbysonicPlugin;

public class PluginConfiguration : BasePluginConfiguration
{
    public string ServiceUrl { get; set; } = "http://localhost:8765";
}
