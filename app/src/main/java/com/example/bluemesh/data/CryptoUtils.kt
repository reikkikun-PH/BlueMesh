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

        fun encryptDecryptXOR(data: ByteArray, aesKey: ByteArray, messageId: Int): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            
            val buffer = ByteBuffer.allocate(aesKey.size + 4)
            buffer.put(aesKey)
            buffer.putInt(messageId)
            val keystream = digest.digest(buffer.array())
            
            val result = ByteArray(data.size)
            for (i in data.indices) {
                result[i] = (data[i].toInt() xor keystream[i % keystream.size].toInt()).toByte()
            }
            return result
        }
    }
}
