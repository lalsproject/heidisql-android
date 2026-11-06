package app.heidimobile.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts secrets with an AES-256-GCM key that lives in the Android Keystore (API 23+). */
object Vault {
    private const val ALIAS = "heidimobile_secrets"
    private const val IV_LEN = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(b64: String): String {
        if (b64.isEmpty()) return ""
        return try {
            val all = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, IV_LEN)))
            String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            "" // key lost (e.g. restored on another device): user has to re-enter the secret
        }
    }
}

/** Persists the session manager list (HeidiSQL keeps it in the registry / portable settings file). */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "sessions.json")
    val sqliteDir: File = File(context.filesDir, "sqlite").apply { mkdirs() }

    fun load(): List<Session> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Session>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun toJson(s: Session) = JSONObject().apply {
        put("id", s.id); put("name", s.name); put("engine", s.engine.name)
        put("host", s.host); put("port", s.port); put("user", s.user)
        put("password", Vault.encrypt(s.password)); put("database", s.database)
        put("useSsl", s.useSsl); put("sqliteFile", s.sqliteFile)
        put("sshEnabled", s.sshEnabled); put("sshHost", s.sshHost); put("sshPort", s.sshPort)
        put("sshUser", s.sshUser); put("sshPassword", Vault.encrypt(s.sshPassword))
        put("sshKey", Vault.encrypt(s.sshKey)); put("sshHostKeyFp", s.sshHostKeyFp)
    }

    private fun fromJson(o: JSONObject): Session {
        val engine = runCatching { Engine.valueOf(o.getString("engine")) }.getOrDefault(Engine.MYSQL)
        return Session(
            id = o.getString("id"), name = o.optString("name"), engine = engine,
            host = o.optString("host", "127.0.0.1"), port = o.optInt("port", engine.defaultPort),
            user = o.optString("user"), password = Vault.decrypt(o.optString("password")),
            database = o.optString("database"), useSsl = o.optBoolean("useSsl"),
            sqliteFile = o.optString("sqliteFile"), sshEnabled = o.optBoolean("sshEnabled"),
            sshHost = o.optString("sshHost"), sshPort = o.optInt("sshPort", 22),
            sshUser = o.optString("sshUser"), sshPassword = Vault.decrypt(o.optString("sshPassword")),
            sshKey = Vault.decrypt(o.optString("sshKey")), sshHostKeyFp = o.optString("sshHostKeyFp")
        )
    }
}
