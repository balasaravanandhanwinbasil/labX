package com.sstinc.labx

import android.content.Context
import java.io.IOException

object CredentialsHelper {

    /**
     * Load credentials JSON from assets folder
     * This is a more secure approach than hardcoding credentials in the source code
     */
    fun loadCredentialsFromAssets(context: Context, fileName: String = "credentials.json"): String {
        return try {
            context.assets.open(fileName).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            throw RuntimeException("Failed to load credentials from assets/$fileName. Make sure the file exists and contains valid JSON.", e)
        }
    }

    /**
     * Validate that the credentials JSON has the required fields
     */
    fun validateCredentials(credentialsJson: String): Boolean {
        return try {
            val requiredFields = listOf(
                "type", "project_id", "private_key_id", "private_key",
                "client_email", "client_id", "auth_uri", "token_uri"
            )

            requiredFields.all { field ->
                credentialsJson.contains("\"$field\"")
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Extract project ID from credentials JSON
     */
    fun extractProjectId(credentialsJson: String): String? {
        return try {
            val projectIdPattern = "\"project_id\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            projectIdPattern.find(credentialsJson)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract client email from credentials JSON
     */
    fun extractClientEmail(credentialsJson: String): String? {
        return try {
            val clientEmailPattern = "\"client_email\"\\s*:\\s*\"([^\"]+)\"".toRegex()
            clientEmailPattern.find(credentialsJson)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}