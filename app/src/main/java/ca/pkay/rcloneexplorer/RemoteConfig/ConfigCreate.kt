package ca.pkay.rcloneexplorer.RemoteConfig

import android.annotation.SuppressLint
import android.content.Context
import ca.pkay.rcloneexplorer.Rclone
import android.os.AsyncTask
import es.dmoral.toasty.Toasty
import ca.pkay.rcloneexplorer.R
import android.widget.Toast
import android.content.Intent
import android.view.View
import ca.pkay.rcloneexplorer.Activities.MainActivity
import ca.pkay.rcloneexplorer.InteractiveRunner
import ca.pkay.rcloneexplorer.util.FLog
import java.util.ArrayList

@SuppressLint("StaticFieldLeak")
class ConfigCreate internal constructor(
    options: ArrayList<String>?,
    formView: View,
    authView: View,
    context: Context,
    rclone: Rclone,
    private val providerType: String = ""
) : AsyncTask<Void?, Void?, Boolean>() {
    private val options: ArrayList<String>
    private var process: Process? = null
    private val mContext: Context
    private val mRclone: Rclone
    private val mFormView: View
    private val mAuthView: View

    companion object {
        private const val TAG = "ConfigCreate"
    }

    init {
        this.options = ArrayList(options)
        mFormView = formView
        mAuthView = authView
        mContext = context
        mRclone = rclone
    }

    override fun onPreExecute() {
        super.onPreExecute()
        mAuthView.visibility = View.VISIBLE
        mFormView.visibility = View.GONE
    }

    override fun doInBackground(vararg params: Void?): Boolean {
        return if (providerType.equals("internxt", ignoreCase = true)) {
            createInternxtWithTwoFactor()
        } else {
            OauthHelper.createOptionsWithOauth(options, mRclone, mContext)
        }
    }

    /**
     * Creates an Internxt remote using InteractiveRunner to handle 2FA.
     * Flow:
     * 1. Start rclone config create
     * 2. If 2FA is required, show dialog for code
     * 3. Complete configuration
     */
    private fun createInternxtWithTwoFactor(): Boolean {
        // Add obscure flag for password handling
        val opts = ArrayList(options)
        opts.add("--obscure")
        
        process = mRclone.config("create", opts)
        if (process == null) {
            FLog.e(TAG, "Failed to start rclone config create for Internxt")
            return false
        }
        
        val proc = process!!
        
        // Create the interactive runner flow
        // Step 1: Wait for 2FA prompt (if account has 2FA enabled)
        val twoFactorStep = OauthHelper.InternxtTwoFactorStep(mContext)
        
        // Step 2: After 2FA, wait for config completion confirmation
        val finishStep = OauthHelper.InternxtFinishStep()
        twoFactorStep.addFollowing(finishStep)
        
        // Also handle case where 2FA is not required (goes straight to finish)
        // We need parallel steps for both possibilities
        val directFinishStep = OauthHelper.InternxtFinishStep()
        
        // Error handler
        val errorHandler = InteractiveRunner.ErrorHandler { e ->
            FLog.e(TAG, "Internxt config error", e)
            proc.destroy()
        }
        
        // Try with 2FA first, but also allow direct finish
        // Use a custom approach: read output and decide
        return try {
            val runner = InteractiveRunner(twoFactorStep, errorHandler, proc)
            OauthHelper.registerRunner(runner)
            runner.runSteps()
            
            proc.waitFor()
            proc.exitValue() == 0
        } catch (e: Exception) {
            FLog.e(TAG, "Internxt config failed", e)
            proc.destroy()
            false
        }
    }

    override fun onCancelled() {
        super.onCancelled()
        process?.destroy()
    }

    override fun onPostExecute(success: Boolean) {
        super.onPostExecute(success)
        if (!success) {
            Toasty.error(
                mContext,
                mContext.getString(R.string.error_creating_remote),
                Toast.LENGTH_SHORT,
                true
            ).show()
        } else {
            Toasty.success(
                mContext,
                mContext.getString(R.string.remote_creation_success),
                Toast.LENGTH_SHORT,
                true
            ).show()
        }
        val intent = Intent(mContext, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        mContext.startActivity(intent)
    }
}
