package com.legs.appsforaa.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Signs rewritten car clones with a persistent key owned by this AAAD installation.
 *
 * The original package is never replaced: changing its APK would invalidate the publisher
 * signature and Android would reject the update. A clone gets a new package id and this key, so it
 * installs alongside the original. Keeping the key in Android Keystore means later conversions
 * update the same clone instead of producing a signature mismatch.
 */
object CarifySigner {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "aaad_carify_v1"

    fun sign(input: File, output: File) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!store.containsAlias(ALIAS)) generateKey()

        val privateKey = store.getKey(ALIAS, null) as? PrivateKey
            ?: error("Carify signing key is unavailable")
        val certificate = store.getCertificate(ALIAS) as? X509Certificate
            ?: error("Carify signing certificate is unavailable")
        val signer = ApkSigner.SignerConfig.Builder(
            "AAAD Carify",
            privateKey,
            listOf(certificate),
        ).build()

        output.delete()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(24)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .setCreatedBy("AAAD Carify")
            .build()
            .sign()

        val verified = ApkVerifier.Builder(output)
            .setMinCheckedPlatformVersion(24)
            .build()
            .verify()
        check(verified.isVerified) {
            "Generated APK signature did not verify: ${verified.errors.joinToString()}"
        }
    }

    private fun generateKey() {
        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            KEYSTORE,
        )
        generator.initialize(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setKeySize(2048)
                .setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA512,
                )
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(
                    X500Principal("CN=AAAD Carify, OU=Personal, O=AAAD, C=US")
                )
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - 24L * 60 * 60 * 1000))
                .setCertificateNotAfter(Date(now + 25L * 365 * 24 * 60 * 60 * 1000))
                .build()
        )
        generator.generateKeyPair()
    }
}
