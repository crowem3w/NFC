package org.example.test

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import org.example.test.nfc.NfcAvailability
import org.example.test.nfc.NfcViewModel
import org.example.test.ui.NfcApp
import org.example.test.ui.theme.TestTheme

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private val vm: NfcViewModel by viewModels()
    private var adapter: NfcAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        adapter = NfcAdapter.getDefaultAdapter(this)
        setContent {
            TestTheme {
                NfcApp(
                    vm = vm,
                    onOpenNfcSettings = { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val a = adapter
        vm.availability = when {
            a == null -> NfcAvailability.Unsupported
            !a.isEnabled -> NfcAvailability.Disabled
            else -> NfcAvailability.Ready
        }
        // Reader mode gives us every tag while we're in the foreground,
        // with no intent filters and no chance of another app grabbing it.
        runCatching {
            a?.enableReaderMode(
                this,
                this,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V,
                null,
            )
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { adapter?.disableReaderMode(this) }
    }

    override fun onTagDiscovered(tag: Tag) = vm.onTag(tag)
}
