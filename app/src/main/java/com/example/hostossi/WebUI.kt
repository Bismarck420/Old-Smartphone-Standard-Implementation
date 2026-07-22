package com.example.hostossi

import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.example.hostossi.databinding.FragmentWebUIBinding

private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

class WebUI : Fragment() {
    private var param1: String? = null
    private var param2: String? = null
    
    private var _binding: FragmentWebUIBinding? = null
    private val binding get() = _binding!!
    private var dashboardUrls: List<String> = emptyList()
    private var dashboardUrlIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            param1 = it.getString(ARG_PARAM1)
            param2 = it.getString(ARG_PARAM2)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_web_u_i, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWebUIBinding.bind(view)

        configureViewerChrome()
        setupWebView()
        refreshWebUI()
    }

    private fun configureViewerChrome() {
        val mode = PreferenceManager.getDefaultSharedPreferences(requireContext())
            .getString("deviceMode", "host")
        val isViewer = mode == "viewer"
        val immersiveWeb = (isViewer || mode == "client") &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        binding.dashboard.text = if (isViewer) getString(R.string.viewer_title) else "OSSI Dashboard"
        binding.dashboardSubtitle.text = if (isViewer) {
            getString(R.string.viewer_summary)
        } else if (mode == "client") {
            "Local Client dashboard"
        } else {
            "Connected Client dashboard"
        }
        binding.viewerModeBadge.isVisible = isViewer && !immersiveWeb
        binding.viewerModeBadge.setTextColor(Color.WHITE)
        binding.dashboardHeader.isVisible = !immersiveWeb

        if (immersiveWeb) {
            binding.webCard.radius = 0f
            binding.webCard.updateLayoutParams<ConstraintLayout.LayoutParams> {
                topToBottom = ConstraintLayout.LayoutParams.UNSET
                topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                setMargins(0, 0, 0, 0)
            }
        }
    }

    private fun setupWebView() {
        val myWebView: WebView = binding.webRenderer
        
        myWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        // Add Javascript interface for reliable retry
        myWebView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun retry() {
                requireActivity().runOnUiThread {
                    Log.d("WebUI", "Retry triggered via JS interface")
                    refreshWebUI()
                }
            }
        }, "AndroidBridge")

        myWebView.webViewClient = object : WebViewClient() {
            private var hasError = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                
                // Only show the native spinner for actual web/network URLs
                // This keeps it off the local error page and 'about:blank'
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    hasError = false
                    if (_binding != null) {
                        binding.webViewProgressBar.isVisible = true
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // Hide the native spinner whenever any page load finishes (success or error page)
                if (_binding != null) {
                    binding.webViewProgressBar.isVisible = false
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true && !hasError) {
                    hasError = true
                    if (!loadNextDashboardRoute()) {
                        handleError(view, error?.description?.toString() ?: "Connection failed")
                    }
                }
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                if (request?.isForMainFrame == true && !hasError) {
                    hasError = true
                    if (!loadNextDashboardRoute()) {
                        handleError(view, "Server error: ${errorResponse?.statusCode}")
                    }
                }
            }

            private fun handleError(view: WebView?, message: String) {
                hasError = true
                Log.e("WebUI", "WebView error: $message")
                
                if (_binding != null) {
                    binding.webViewProgressBar.isVisible = false
                }
                
                val errorHtml = """
                    <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                            <style>
                                body { background-color: #0F1115; color: white; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; font-family: sans-serif; margin: 0; padding: 24px; text-align: center; }
                                .card { background: #1F2630; border-radius: 16px; padding: 40px 24px; max-width: 360px; width: 100%; box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3); border: 1px solid rgba(255,255,255,0.05); }
                                .icon { background: rgba(239, 68, 68, 0.1); width: 64px; height: 64px; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 0 auto 24px auto; }
                                h2 { color: #ffffff; margin: 0 0 12px 0; font-size: 22px; font-weight: 700; }
                                p { color: #94a3b8; margin: 0 0 32px 0; font-size: 15px; line-height: 1.5; }
                                .btn { background-color: #ef4444; color: white; border: none; width: 100%; padding: 14px; border-radius: 10px; font-weight: 600; font-size: 16px; cursor: pointer; transition: background 0.2s; position: relative; }
                                .btn:active { background-color: #dc2626; }
                                .btn:disabled { background-color: #4b1a1a; color: #94a3b8; cursor: not-allowed; }
                                .spinner { display: none; width: 18px; height: 18px; border: 3px solid rgba(255,255,255,0.3); border-radius: 50%; border-top-color: #fff; animation: spin 1s ease-in-out infinite; position: absolute; left: 16px; top: 50%; margin-top: -9px; }
                                @keyframes spin { to { transform: rotate(360deg); } }
                                .loading .spinner { display: block; }
                                .loading .btn-text { margin-left: 24px; }
                            </style>
                        </head>
                        <body>
                            <div class="card">
                                <div class="icon">
                                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
                                </div>
                                <h2>Connection Failed</h2>
                                <p>${message.replace("'", "\\'")}</p>
                                <button id="retryBtn" class="btn" onclick="startRetry()">
                                    <div class="spinner"></div>
                                    <span id="btnText" class="btn-text">Try Again</span>
                                </button>
                            </div>
                            <script>
                                function startRetry() {
                                    const btn = document.getElementById('retryBtn');
                                    const text = document.getElementById('btnText');
                                    btn.disabled = true;
                                    btn.classList.add('loading');
                                    text.innerText = 'Retrying...';
                                    
                                    // Use the Android Bridge to trigger a native reload
                                    if (window.AndroidBridge) {
                                        window.AndroidBridge.retry();
                                    } else {
                                        window.location.reload();
                                    }
                                }
                            </script>
                        </body>
                    </html>
                """.trimIndent()
                view?.loadDataWithBaseURL(null, errorHtml, "text/html", "UTF-8", null)
            }
        }
        
        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d("WebViewConsole", "${consoleMessage.message()} -- From line ${consoleMessage.lineNumber()} of ${consoleMessage.sourceId()}")
                return true
            }
        }
    }

    private fun loadNextDashboardRoute(): Boolean {
        if (dashboardUrlIndex + 1 >= dashboardUrls.size || _binding == null) return false
        dashboardUrlIndex += 1
        val fallbackUrl = dashboardUrls[dashboardUrlIndex]
        Log.w("WebUI", "Primary Client route failed, trying $fallbackUrl")
        binding.webRenderer.loadUrl(fallbackUrl)
        return true
    }

    private fun refreshWebUI() {
        if (_binding == null) return
        
        // Reset state and stop any current loading to ensure the next request is processed fresh
        binding.webRenderer.stopLoading()
        binding.webViewProgressBar.isVisible = true
        
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        val deviceMode = sharedPreferences.getString("deviceMode", "default")

        if (deviceMode == "host") {
            KtorServer.syncProjectsToClient(requireContext())
        }

        dashboardUrls = if (deviceMode == "client") {
            listOf("http://127.0.0.1:8080/")
        } else {
            ClientEndpointResolver.candidates(requireContext())
                .map { endpoint -> "http://${endpoint.address}:8080/" }
        }
        dashboardUrlIndex = 0
        val dashboardUrl = dashboardUrls.firstOrNull() ?: "http://127.0.0.1:0/"

        Log.d("WebUI", "Refreshing WebUI: $dashboardUrl")
        binding.webRenderer.loadUrl(dashboardUrl)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance(param1: String, param2: String) =
            WebUI().apply {
                arguments = Bundle().apply {
                    putString(ARG_PARAM1, param1)
                    putString(ARG_PARAM2, param2)
                }
            }
    }
}
