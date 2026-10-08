package com.arizona.fosa.mobile

/** Read negotiated SDP without invalidating WebRTC Java transceiver wrappers. */
object AudioNegotiation {
    private data class Media(val mid:String,val direction:String,val active:Boolean)
    private fun audio(sdp:String):List<Media> = sdp.split(Regex("(?m)(?=^m=)")).mapIndexedNotNull{index,part->
        val lines=part.split(Regex("\\r?\\n"));val m=lines.firstOrNull{it.startsWith("m=audio ")} ?: return@mapIndexedNotNull null
        Media(lines.firstOrNull{it.startsWith("a=mid:")}?.removePrefix("a=mid:") ?: index.toString(),lines.firstOrNull{it in listOf("a=sendrecv","a=sendonly","a=recvonly","a=inactive")}?.removePrefix("a=") ?: "sendrecv",m.split(' ').getOrNull(1)!="0")
    }
    fun sending(local:String,remote:String):Boolean {val incoming=audio(remote).associateBy{it.mid};return audio(local).any{a->val b=incoming[a.mid];a.active&&b?.active==true&&a.direction in listOf("sendrecv","sendonly")&&b.direction in listOf("sendrecv","recvonly")}}
}
