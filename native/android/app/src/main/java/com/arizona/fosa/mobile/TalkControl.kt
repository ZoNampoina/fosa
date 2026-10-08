package com.arizona.fosa.mobile

/** Manual arming is always required. Transport/permission loss never rearms. */
class TalkControl {
    var mode="HOLD";private set
    var armed=false;private set
    var threshold=-36.0
    var closeDelayMs=650L
    var timeoutMs=0L
    var noiseReduction=true
    private var deadline=Long.MAX_VALUE
    private var lastVoice=Long.MIN_VALUE
    private var open=false
    val requested:Boolean get()=armed&&(mode!="AUTO"||open)
    fun mode(value:String){require(value in listOf("HOLD","TAP","AUTO"));stop();mode=value}
    fun active(value:Boolean,now:Long){if(!value){stop();return};armed=true;open=mode!="AUTO";lastVoice=Long.MIN_VALUE;deadline=now+if(mode=="HOLD")30000 else if(timeoutMs>0)timeoutMs else Long.MAX_VALUE-now}
    fun level(db:Double?,now:Long){if(mode!="AUTO"||!armed)return;if(db!=null&&db.isFinite()&&db>=threshold){lastVoice=now;open=true};if(lastVoice==Long.MIN_VALUE||now-lastVoice>closeDelayMs)open=false;tick(now)}
    fun tick(now:Long){if(armed&&now>=deadline)stop()}
    fun stop(){armed=false;open=false;deadline=Long.MAX_VALUE;lastVoice=Long.MIN_VALUE}
}
