using System;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using MediaBrowser.Controller.Net;
using MediaBrowser.Model.Services;

namespace EmbysonicPlugin.Api;

[Route("/emby-sonic/status", "GET")]
[Authenticated]
public class GetSonicStatus : IReturn<object> { }

[Route("/emby-sonic/status/errors", "GET")]
[Authenticated]
public class GetSonicStatusErrors : IReturn<object> { }

[Route("/emby-sonic/library/scan", "POST")]
[Authenticated]
public class PostSonicLibraryScan : IReturn<object> { }

[Route("/emby-sonic/library/build-mixes", "POST")]
[Authenticated]
public class PostSonicBuildMixes : IReturn<object> { }

/// <summary>
/// Server-side proxy to the Python coordinator, used by the plugin config page.
///
/// The config page used to call the coordinator directly from the browser —
/// cross-origin from Emby's own dashboard origin whenever Emby is reached via
/// a reverse-proxy domain and the coordinator isn't on that same origin. Some
/// proxy setups don't relay the coordinator's CORS headers, so the browser
/// blocks the request outright (reported as "Service: offline" with a CORS
/// preflight failure in devtools, even though the coordinator itself is fine
/// and CORS-permissive). Routing through this same-origin Emby API endpoint
/// means the browser never talks to the coordinator directly — only this
/// server-to-server call does, from a request that already reached Emby.
///
/// (An earlier attempt at this used an ASP.NET Core MVC controller, but Emby
/// does not auto-route plugin MVC controllers — see docs/spec.md. IService +
/// [Route] is Emby's actual plugin API convention, auto-discovered from the
/// loaded plugin assembly.)
///
/// Verified live on Emby 4.10.0.17-BETA (2026-07-04): loads cleanly, no
/// exceptions, config page shows "Service: online" via the proxy path.
/// </summary>
public class SonicProxyService : IService, IRequiresRequest
{
    private static readonly HttpClient _http = new();

    public IRequest Request { get; set; } = null!;

    private readonly IHttpResultFactory _resultFactory;

    public SonicProxyService(IHttpResultFactory resultFactory)
    {
        _resultFactory = resultFactory;
    }

    public object Get(GetSonicStatus request) => Forward("/sonic/status", HttpMethod.Get);
    public object Get(GetSonicStatusErrors request) => Forward("/sonic/status/errors", HttpMethod.Get);
    public object Post(PostSonicLibraryScan request) => Forward("/sonic/library/scan", HttpMethod.Post);
    public object Post(PostSonicBuildMixes request) => Forward("/sonic/library/build-mixes", HttpMethod.Post);

    private object Forward(string path, HttpMethod method)
    {
        var (bytes, contentType, statusCode) = ForwardAsync(path, method).GetAwaiter().GetResult();
        Request.Response.StatusCode = statusCode;
        return _resultFactory.GetResult(Request, (ReadOnlyMemory<byte>)bytes, contentType, null);
    }

    private async Task<(byte[] Bytes, string ContentType, int StatusCode)> ForwardAsync(string path, HttpMethod method)
    {
        var serviceUrl = Plugin.Instance?.Configuration.ServiceUrl?.TrimEnd('/') ?? "http://localhost:8765";
        using var upstream = new HttpRequestMessage(method, serviceUrl + path);

        // Prefer the caller's own X-Emby-Token (the config page always sends
        // it); fall back to the plugin's configured API key so this still
        // works if that header is ever absent.
        var token = Request.Headers["X-Emby-Token"];
        if (string.IsNullOrEmpty(token))
            token = Plugin.Instance?.Configuration.ApiKey;
        if (!string.IsNullOrEmpty(token))
            upstream.Headers.TryAddWithoutValidation("X-Emby-Token", token);

        if (method == HttpMethod.Post)
            upstream.Content = new StringContent("{}", Encoding.UTF8, "application/json");

        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(upstream).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            var err = JsonSerializer.SerializeToUtf8Bytes(new
            {
                error = "Emby Sonic service unavailable",
                detail = ex.Message,
            });
            return (err, "application/json", 502);
        }

        var body = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
        var contentType = response.Content.Headers.ContentType?.ToString() ?? "application/json";
        return (body, contentType, (int)response.StatusCode);
    }
}
