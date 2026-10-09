package com.arizona.fosa.mobile

/** Read negotiated SDP without invalidating WebRTC Java transceiver wrappers. */
object AudioNegotiation {
    private data class Media(val mid:String,val direction:String,val active:Boolean,val tracks:Set<String>)
    private fun audio(sdp:String):List<Media> = sdp.split(Regex("(?m)(?=^m=)")).mapIndexedNotNull{index,part->
        val lines=part.split(Regex("\\r?\\n"));val m=lines.firstOrNull{it.startsWith("m=audio ")} ?: return@mapIndexedNotNull null
        val mid=lines.firstOrNull{it.startsWith("a=mid:")}?.removePrefix("a=mid:") ?: index.toString()
        val bundled=lines.contains("a=bundle-only")&&sdp.lineSequence().any{it.trim().startsWith("a=group:BUNDLE ")&&mid in it.trim().split(' ').drop(1)}
        val tracks=lines.mapNotNull{line->when{line.startsWith("a=msid:")->line.removePrefix("a=msid:").split(' ').getOrNull(1);line.startsWith("a=ssrc:")&&line.contains(" msid:")->line.substringAfter(" msid:").split(' ').getOrNull(1);else->null}}.toSet()
        Media(mid,lines.firstOrNull{it in listOf("a=sendrecv","a=sendonly","a=recvonly","a=inactive")}?.removePrefix("a=") ?: "sendrecv",m.split(' ').getOrNull(1)!="0"||bundled,tracks)
    }
    fun sending(local:String,remote:String,track:String?=null):Boolean {val incoming=audio(remote).associateBy{it.mid};return audio(local).any{a->val b=incoming[a.mid];(track==null||track in a.tracks)&&a.active&&b?.active==true&&a.direction in listOf("sendrecv","sendonly")&&b.direction in listOf("sendrecv","recvonly")}}
}
