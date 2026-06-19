package com.example.bluemesh.data

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

class CryptoUtils {
    companion object {
        fun generateECKeyPair(): KeyPair {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(256) // secp256r1
            return kpg.generateKeyPair()
        }

        fun generateSharedSecret(myPrivateKey: PrivateKey, peerPublicKeyBytes: ByteArray): ByteArray {
            val kf = KeyFactory.getInstance("EC")
            val publicKeySpec = X509EncodedKeySpec(peerPublicKeyBytes)
            val peerPublicKey = kf.generatePublic(publicKeySpec)
            
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(myPrivateKey)
            ka.doPhase(peerPublicKey, true)
            return ka.generateSecret()
        }

        fun deriveAESKey(sharedSecret: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(sharedSecret)
        }

        fun encryptAESGCM(plaintext: ByteArray, key: ByteArray): ByteArray {
            val iv = ByteArray(12)
            java.security.SecureRandom().nextBytes(iv)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val parameterSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, parameterSpec)
            val ciphertext = cipher.doFinal(plaintext)
            
            val result = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, result, 0, iv.size)
            System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
            return result
        }

        fun decryptAESGCM(ciphertextWithIv: ByteArray, key: ByteArray): ByteArray {
            if (ciphertextWithIv.size < 12) {
                throw IllegalArgumentException("Ciphertext too short")
            }
            val iv = ciphertextWithIv.copyOfRange(0, 12)
            val ciphertext = ciphertextWithIv.copyOfRange(12, ciphertextWithIv.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val parameterSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            val keySpec = javax.crypto.spec.SecretKeySpec(key, "AES")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, parameterSpec)
            return cipher.doFinal(ciphertext)
        }
    }
}
