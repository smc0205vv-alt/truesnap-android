package org.witness.proofmode

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.witness.proofmode.PermissionActivity.Companion.hasPermissions
import org.witness.proofmode.ProofMode.PREF_CREDENTIALS_PRIMARY
import org.witness.proofmode.databinding.ActivitySettingsBinding
import org.witness.proofmode.org.witness.proofmode.share.FilebaseSettingsActivity
import org.witness.proofmode.storage.FilebaseConfig


class SettingsActivity : AppCompatActivity() {
    private lateinit var mPrefs: SharedPreferences
    private lateinit var switchNetwork: CheckBox
    private lateinit var switchDevice: CheckBox
    private lateinit var switchNotarize: CheckBox
    private lateinit var switchCredentials: CheckBox
    private lateinit var switchAI: CheckBox
    private lateinit var switchAutoImport: CheckBox
    private lateinit var switchAutoSync: CheckBox

    private lateinit var binding:ActivitySettingsBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Setup toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setTitle("")
        binding.toolbar.setTitleTextColor(getColor(R.color.colorPrimaryDark))
        binding.toolbar.setNavigationIconTint(getColor(R.color.colorPrimaryDark))

        //supportActionBar?.setDisplayShowTitleEnabled(false)

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        switchNetwork = binding.contentSettings.switchNetwork
        switchDevice = binding.contentSettings.switchDevice
        switchNotarize = binding.contentSettings.switchNotarize
        switchCredentials = binding.contentSettings.switchCR
        switchAI = binding.contentSettings.switchAI
        switchAutoImport = binding.contentSettings.switchAutoImport
        switchAutoSync = binding.contentSettings.switchAutoSync


        updateUI()

        switchNetwork.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            if (isChecked) {
                if (!askForPermission(
                        Manifest.permission.ACCESS_NETWORK_STATE,
                        REQUEST_CODE_NETWORK_STATE,
                        0
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, true).commit()
                }
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_NETWORK, false).commit()
            }
            updateUI()
        }
        switchDevice.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            if (isChecked) {
                if (!askForPermission(
                        Manifest.permission.READ_PHONE_STATE,
                        REQUEST_CODE_READ_PHONE_STATE,
                        0
                    )
                ) {
                    mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, true).commit()
                }
            } else {
                mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_PHONE, false).commit()
            }
            updateUI()
        }

        // The Notary cell opens the dedicated Notary settings screen (master enable +
        // per-provider toggles + Nostr identity); the checkbox is a read-only indicator.
        binding.contentSettings.cellNotary.setOnClickListener {
            startActivity(Intent(this, NotarySettingsActivity::class.java))
        }

        // The Credentials cell is not a toggle: tapping it always opens the signing
        // settings, where the user picks Remote / Local / Disabled. The checkbox is a
        // read-only indicator of that mode (off only when signing is Disabled).
        binding.contentSettings.cellCredentials.setOnClickListener {
            startActivity(Intent(this, SigningSettingsActivity::class.java))
        }

        switchAI.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            mPrefs.edit().putBoolean(ProofMode.PREF_OPTION_BLOCK_AI, isChecked)
                .commit()
            updateUI()
        }


        // Setup Filebase settings button
        switchAutoSync.setOnCheckedChangeListener {_: CompoundButton?, isChecked: Boolean ->

            mPrefs.edit().putBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, isChecked).commit()

            if (isChecked) {
                val intent = Intent(this, FilebaseSettingsActivity::class.java)
                startActivity(intent)
            }
            else {

            }


        }

        switchAutoImport.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->

            /**
            mPrefs.edit().putBoolean(ProofMode.PREFS_DOPROOF, isChecked).commit()

            if (isChecked)
                (application as ProofModeApp).init(this)
            else
                (application as ProofModeApp).cancel(this)
            **/
            //Toast.makeText(this, getString(R.string.coming_soon),Toast.LENGTH_LONG).show()


        }

    }

    private val REQ_ACCOUNT_CHOOSER = 9999;

    private fun showIdentityChooser () {

        /**
        val intent = AccountPicker.newChooseAccountIntent(
            AccountPicker.AccountChooserOptions.Builder()
                .build()
        )

        startActivityForResult(intent, REQ_ACCOUNT_CHOOSER)
        **/
    }

    private fun updateUI() {
        switchNetwork.isChecked =
            mPrefs.getBoolean(
                ProofMode.PREF_OPTION_NETWORK,
                ProofMode.PREF_OPTION_NETWORK_DEFAULT
            )
        switchDevice.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_PHONE, ProofMode.PREF_OPTION_PHONE_DEFAULT)
        switchNotarize.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_NOTARY, ProofMode.PREF_OPTION_NOTARY_DEFAULT)

        val credentialsEnabled =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_CREDENTIALS, ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT)
        switchCredentials.isChecked = credentialsEnabled

        switchAI.isChecked =
            mPrefs.getBoolean(ProofMode.PREF_OPTION_BLOCK_AI, ProofMode.PREF_OPTION_AI_DEFAULT)

        switchAI.isEnabled = credentialsEnabled

        switchAutoSync.isChecked =
            mPrefs.getBoolean(FilebaseConfig.PREF_FILEBASE_ENABLED, false)

        switchAutoImport.isChecked =
            mPrefs.getBoolean(ProofMode.PREFS_DOPROOF, false)

        //disable auto import for now
        switchAutoImport.isEnabled = false

        updateCredentialsDesc()
    }

    private fun updateCredentialsDesc() {
        val textCRDesc = binding.contentSettings.textCRDesc
        val credentialsEnabled = mPrefs.getBoolean(
            ProofMode.PREF_OPTION_CREDENTIALS,
            ProofMode.PREF_OPTION_CREDENTIALS_DEFAULT
        )
        val isRemote = mPrefs.getBoolean(
            ProofMode.PREF_OPTION_REMOTE_SIGNING,
            ProofMode.PREF_OPTION_REMOTE_SIGNING_DEFAULT
        )
        textCRDesc.text = when {
            !credentialsEnabled -> getString(R.string.settings_credentials_disabled)
            isRemote -> getString(R.string.settings_credentials_remote)
            else -> getString(R.string.settings_credentials_local)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_NETWORK_STATE -> {
                if (hasPermissions(this, arrayOf(Manifest.permission.ACCESS_NETWORK_STATE))) {
                    mPrefs.edit(commit = true) { putBoolean(ProofMode.PREF_OPTION_NETWORK, true) }
                }
                updateUI()
            }
            REQUEST_CODE_READ_PHONE_STATE -> {
                if (hasPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE))) {
                    mPrefs.edit(commit = true) { putBoolean(ProofMode.PREF_OPTION_PHONE, true) }
                }
                updateUI()
            }
            REQ_ACCOUNT_CHOOSER -> {
                val accountName = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);

                //only if the account is changed, should we change the credentials
                if (!mPrefs.getString(PREF_CREDENTIALS_PRIMARY,"").equals(accountName)) {
                    mPrefs.edit(commit = true) { putString(PREF_CREDENTIALS_PRIMARY, accountName) }
                }

            }

        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId
        if (id == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun askForPermission(permission: String, requestCode: Int, layoutId: Int): Boolean {
        val permissions = arrayOf(permission)
        if (!hasPermissions(this, permissions)) {
            val intent = Intent(this, PermissionActivity::class.java)
            intent.putExtra(PermissionActivity.ARG_PERMISSIONS, permissions)
            if (layoutId != 0) {
                intent.putExtra(PermissionActivity.ARG_LAYOUT_ID, R.layout.permission_location)
            }
            startActivityForResult(intent, requestCode)
            return true
        }
        return false
    }

    companion object {
        private const val REQUEST_CODE_NETWORK_STATE = 2
        private const val REQUEST_CODE_READ_PHONE_STATE = 3
    }
}