package com.arizona.fosa.mobile

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.*
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.*
import java.security.cert.X509Certificate
import java.math.BigInteger
import java.util.Date
import javax.net.ssl.*

/** Per-installation CA. Keys stay in app-private storage; only the public root
 * is served. Browser trust requires explicit installation and verification. */
class LanTls(ctx:Context,addresses:List<String>) {
    private val provider=BouncyCastleProvider()
    private val store=KeyStore.getInstance("PKCS12")
    private val file=java.io.File(ctx.noBackupFilesDir,"fosa-local-web.p12")
    private val password="FOSA-app-private-keystore".toCharArray()
    val ca:ByteArray
    val factory:SSLServerSocketFactory
    init {
        if(file.exists())file.inputStream().use{store.load(it,password)}else{
            store.load(null,password);val pair=key();val name=X500Name("CN=FOSA Local Host ${PairingQr.nonce().take(8)}")
            val cert=cert(name,name,pair.public,pair.private,true,emptyList())
            store.setKeyEntry("root",pair.private,password,arrayOf(cert));file.outputStream().use{store.store(it,password)}
        }
        val root=store.getCertificate("root") as X509Certificate;ca=root.encoded
        val pair=key();val cert=cert(X500Name(root.subjectX500Principal.name),X500Name("CN=FOSA Web local"),pair.public,store.getKey("root",password) as PrivateKey,false,addresses)
        val runtime=KeyStore.getInstance("PKCS12").apply{load(null,password);setKeyEntry("web",pair.private,password,arrayOf(cert,root))}
        val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(runtime,password)}
        factory=SSLContext.getInstance("TLS").apply{init(km.keyManagers,null,SecureRandom())}.serverSocketFactory
    }
    private fun key()=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
    private fun cert(issuer:X500Name,subject:X500Name,key:PublicKey,signer:PrivateKey,root:Boolean,addresses:List<String>):X509Certificate {
        val now=System.currentTimeMillis()
        val builder=JcaX509v3CertificateBuilder(issuer,BigInteger(128,SecureRandom()).abs(),Date(now-86400000),Date(now+if(root)315360000000L else 7776000000L),subject,key)
        builder.addExtension(Extension.basicConstraints,true,BasicConstraints(root))
        builder.addExtension(Extension.keyUsage,true,KeyUsage(if(root)KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        if(!root){builder.addExtension(Extension.extendedKeyUsage,false,ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));builder.addExtension(Extension.subjectAlternativeName,false,GeneralNames((addresses.distinct().filter{LanAddress.privateV4(it)}.map{GeneralName(GeneralName.iPAddress,it)}+GeneralName(GeneralName.dNSName,"localhost")).toTypedArray()))}
        return JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(signer)))
    }
}
