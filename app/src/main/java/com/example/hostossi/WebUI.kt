package com.example.hostossi

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.hostossi.databinding.FragmentProjectViewBinding
import com.example.hostossi.databinding.FragmentWebUIBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// TODO: Rename parameter arguments, choose names that match
// the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
private const val ARG_PARAM1 = "param1"
private const val ARG_PARAM2 = "param2"

private var _binding: FragmentWebUIBinding? = null
private val binding get() = _binding!!

/**
 * A simple [Fragment] subclass.
 * Use the [WebUI.newInstance] factory method to
 * create an instance of this fragment.
 */
class WebUI : Fragment() {
    // TODO: Rename and change types of parameters
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
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_web_u_i, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = FragmentWebUIBinding.bind(view)

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity())
        val ipAddressKey = sharedPreferences.getString("client_IP", "default")
        val clientHostKey = sharedPreferences.getString("deviceMode", "default")

        val myWebView: WebView = binding.webRenderer
        if(clientHostKey == "client"){
            myWebView.loadUrl("http://localhost:8080/tasks")
        }
        else{
            myWebView.loadUrl("http://$ipAddressKey:8080/tasks")
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 4. WICHTIG: Speicherleck verhindern!
        // Binding null setzen, wenn die View weg ist.
        _binding = null
    }


    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param param1 Parameter 1.
         * @param param2 Parameter 2.
         * @return A new instance of fragment WebUI.
         */
        // TODO: Rename and change types and number of parameters
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