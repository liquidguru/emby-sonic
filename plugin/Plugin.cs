using System;
using System.Collections.Generic;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Drawing;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;

namespace EmbysonicPlugin;

public class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
{
    public Plugin(IApplicationPaths applicationPaths, IXmlSerializer xmlSerializer)
        : base(applicationPaths, xmlSerializer)
    {
        Instance = this;
    }

    public static Plugin? Instance { get; private set; }

    public override string Name => "Emby Sonic";

    public override Guid Id => new("a1b2c3d4-e5f6-7890-abcd-ef1234567890");

    public override string Description =>
        "Neural audio analysis for Emby — sonically intelligent discovery via PANNs CNN14 embeddings.";

    public IEnumerable<PluginPageInfo> GetPages() =>
    [
        new PluginPageInfo
        {
            Name = "EmbysonicConfig",
            EmbeddedResourcePath = $"{GetType().Namespace}.Configuration.configPage.html",
            DisplayName = "Emby Sonic",
        }
    ];
}
