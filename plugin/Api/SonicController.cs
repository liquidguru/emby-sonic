using System;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using MediaBrowser.Controller.Net;
using MediaBrowser.Model.Services;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace EmbysonicPlugin.Api;

/// <summary>
/// Proxies all /emby-sonic/* requests to the Python coordinator service.
/// Forwards the X-Emby-Token from the Emby request so the coordinator can
/// validate it against /Users/Me as normal.
/// </summary>
[ApiController]
[Microsoft.AspNetCore.Mvc.Route("emby-sonic")]
public class SonicController : ControllerBase
{
    private static readonly HttpClient _http = new();

    [HttpGet("{**path}")]
    [HttpPost("{**path}")]
    [HttpDelete("{**path}")]
    public async Task<IActionResult> Proxy(string path)
    {
        var serviceUrl = Plugin.Instance?.Configuration.ServiceUrl?.TrimEnd('/')
                         ?? "http://localhost:8765";

        // Build target URL, preserving query string
        var target = $"{serviceUrl}/sonic/{path}";
        if (Request.QueryString.HasValue)
            target += Request.QueryString.Value;

        using var upstream = new HttpRequestMessage(new HttpMethod(Request.Method), target);

        // Forward auth token
        if (Request.Headers.TryGetValue("X-Emby-Token", out var token))
            upstream.Headers.TryAddWithoutValidation("X-Emby-Token", (string?)token);

        // Forward body for POST/PUT
        if (Request.ContentLength > 0)
        {
            upstream.Content = new StreamContent(Request.Body);
            if (Request.ContentType is not null)
                upstream.Content.Headers.TryAddWithoutValidation("Content-Type", Request.ContentType);
        }

        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(upstream, HttpCompletionOption.ResponseHeadersRead);
        }
        catch (Exception ex)
        {
            return StatusCode(502, new { error = "Emby Sonic service unavailable", detail = ex.Message });
        }

        Response.StatusCode = (int)response.StatusCode;
        foreach (var h in response.Headers)
            Response.Headers.Append(h.Key, h.Value.ToString());

        var body = await response.Content.ReadAsStreamAsync();
        return new FileStreamResult(body, response.Content.Headers.ContentType?.ToString() ?? "application/json");
    }
}
