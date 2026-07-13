package com.example.hostossi

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.example.hostossi.databinding.FragmentWebUIBinding

private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

private var _binding: FragmentWebUIBinding? = null
private val binding get() = _binding!!

class WebUI : Fragment() {
    private var param1: String? = null
    private var param2: String? = null

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

        // Ensure the internal server is synced with the latest data from the database
        // so the dashboard reflects the current state immediately on load.
        KtorServer.syncProjectsToClient(requireContext())

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        val ipAddressKey = sharedPreferences.getString("client_IP", "")?.trim().orEmpty()
        val deviceMode = sharedPreferences.getString("deviceMode", "default")

        val myWebView: WebView = binding.webRenderer
        val progressBar: ProgressBar? = view.findViewById(R.id.webViewProgressBar)

        myWebView.webViewClient = object : WebViewClient() {
            private var hasError = false

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hasError = false
                progressBar?.isVisible = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!hasError) {
                    progressBar?.isVisible = false
                }
            }

            // Legacy error handling
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                    handleError(view, description ?: "Unknown error")
                }
            }

            // Modern error handling
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    handleError(view, error?.description?.toString() ?: "Connection failed")
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                if (request?.isForMainFrame == true) {
                    handleError(view, "Server error: ${errorResponse?.statusCode}")
                }
            }

            private fun handleError(view: WebView?, message: String) {
                hasError = true
                Log.e("WebUI", "WebView error: $message")
                progressBar?.isVisible = false
                
                val errorHtml = """
                    <html>
                        <body style="background-color: #0F1115; color: white; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; font-family: sans-serif; margin: 0; padding: 24px; text-align: center;">
                            <div style="background: #1F2630; border-radius: 16px; padding: 40px 24px; max-width: 360px; width: 100%; box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.3);">
                                <div style="background: rgba(239, 68, 68, 0.1); width: 64px; height: 64px; border-radius: 50%; display: flex; align-items: center; justify-content: center; margin: 0 auto 24px auto;">
                                    <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
                                </div>
                                <h2 style="color: #ffffff; margin: 0 0 12px 0; font-size: 22px; font-weight: 700;">Connection Failed</h2>
                                <p style="color: #94a3b8; margin: 0 0 32px 0; font-size: 15px; line-height: 1.5;">${message.replace("'", "\\'")}</p>
                                <button onclick="window.location.reload()" style="background-color: #ef4444; color: white; border: none; width: 100%; padding: 14px; border-radius: 10px; font-weight: 600; font-size: 16px; cursor: pointer; transition: background 0.2s;">Try Again</button>
                            </div>
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
        
        myWebView.settings.javaScriptEnabled = true
        myWebView.settings.domStorageEnabled = true
        myWebView.settings.loadWithOverviewMode = true
        myWebView.settings.useWideViewPort = true

        val dashboardUrl = if (deviceMode == "client") {
            "http://127.0.0.1:8080/"
        } else {
            "http://$ipAddressKey:8080/"
        }

        Log.d("test", "Loading URL: $dashboardUrl")
        myWebView.loadUrl(dashboardUrl)
    }

    override fun onResume() {
        super.onResume()
        // Reload the WebView when the fragment becomes visible to ensure fresh data
        binding.webRenderer.reload()
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
