package com.arizona.fosa.mobile

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.arizona.fosa.R

object FosaColors { val Background=Color(0xFF0D1218);val Surface=Color(0xFF161E27);val Elevated=Color(0xFF1E2935);val Accent=Color(0xFF90E9BB);val Text=Color(0xFFF0F4F7);val Secondary=Color(0xFF9EADBC);val Warning=Color(0xFFFFC46B);val Error=Color(0xFFFF7474) }
object FosaSpacing {val XS=4.dp;val S=8.dp;val M=12.dp;val L=16.dp;val XL=24.dp;val XXL=32.dp}
object FosaRadius {val Panel=RoundedCornerShape(12.dp);val Control=RoundedCornerShape(8.dp)}
object FosaElevation {val Flat=0.dp}
object FosaMotion {const val MeterIntervalMs=150;const val PerformanceIntervalMs=500}
val LocalFosaHaptic=compositionLocalOf{true}
val FosaTypography=Typography(
    displayLarge=androidx.compose.ui.text.TextStyle(fontSize=48.sp,fontWeight=FontWeight.Bold,letterSpacing=(-2).sp),
    headlineLarge=androidx.compose.ui.text.TextStyle(fontSize=32.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-1).sp),
    titleLarge=androidx.compose.ui.text.TextStyle(fontSize=22.sp,fontWeight=FontWeight.SemiBold),
    bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=16.sp,lineHeight=23.sp),
    labelSmall=androidx.compose.ui.text.TextStyle(fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.1.sp))
@Composable fun FosaTheme(mode:String="DARK",haptic:Boolean=true,content:@Composable ()->Unit){val dark=mode=="DARK"||mode=="SYSTEM"&&isSystemInDarkTheme();val scheme=if(dark)darkColorScheme(primary=FosaColors.Accent,onPrimary=FosaColors.Background,background=FosaColors.Background,surface=FosaColors.Surface,surfaceVariant=FosaColors.Elevated,onSurface=FosaColors.Text,onSurfaceVariant=FosaColors.Secondary,error=FosaColors.Error) else lightColorScheme(primary=Color(0xFF166D49),onPrimary=Color.White,background=Color(0xFFEDF1F4),surface=Color.White,surfaceVariant=Color(0xFFE1E8EE),onSurface=Color(0xFF15212E),onSurfaceVariant=Color(0xFF4E6070))
    CompositionLocalProvider(LocalFosaHaptic provides haptic){MaterialTheme(colorScheme=scheme,typography=FosaTypography){Surface(color=scheme.background,modifier=Modifier.fillMaxSize()){content()}}}}
@Composable fun FosaLabel(text:String){Text(text.uppercase(),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
@Composable fun FosaStatus(text:String,good:Boolean=true){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Box(Modifier.size(6.dp).clip(CircleShape).background(if(good)MaterialTheme.colorScheme.primary else FosaColors.Warning));Text(text,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurface)}}
@Composable fun FosaAppBar(product:String="MOBILE",status:String="LOCAL",good:Boolean=true){Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){Image(painterResource(R.drawable.fosa_logo),"FOSA",Modifier.size(38.dp));Spacer(Modifier.width(10.dp));Column{Text("FOSA",style=MaterialTheme.typography.titleLarge,letterSpacing=2.sp);FosaLabel(product)};Spacer(Modifier.weight(1f));FosaStatus(status,good)}}
@Composable fun FosaPanel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){Column(modifier.fillMaxWidth().clip(FosaRadius.Panel).background(MaterialTheme.colorScheme.surface).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)}
@Composable fun FosaButton(text:String,modifier:Modifier=Modifier,enabled:Boolean=true,secondary:Boolean=false,onClick:()->Unit){Button(onClick,modifier.heightIn(min=50.dp),enabled=enabled,shape=FosaRadius.Control,elevation=ButtonDefaults.buttonElevation(defaultElevation=0.dp),colors=if(secondary)ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.surfaceVariant,contentColor=MaterialTheme.colorScheme.onSurface) else ButtonDefaults.buttonColors()){Text(text,fontWeight=FontWeight.Bold,fontSize=13.sp)}}
@Composable fun FosaMeter(db:Double?,modifier:Modifier=Modifier){val ratio=if(db!=null&&db.isFinite())((db+60)/60).coerceIn(0.0,1.0).toFloat() else 0f;val back=MaterialTheme.colorScheme.surfaceVariant;val fill=MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(7.dp)){drawRect(back);if(ratio>0)drawRect(fill,size=androidx.compose.ui.geometry.Size(size.width*ratio,size.height))}}
@Composable fun FosaSlider(label:String,value:Float,onChange:(Float)->Unit){Column{Row(Modifier.fillMaxWidth()){FosaLabel(label);Spacer(Modifier.weight(1f));Text(if(value<=0)"MUTE" else "${(20*kotlin.math.log10(value.toDouble())).toInt()} dB",fontFamily=FontFamily.Monospace)};Slider(value,onChange,valueRange=0f..1f)}}
@Composable fun FosaChannel(name:String,role:String,active:Boolean,level:Double?,content:@Composable ()->Unit){FosaPanel{Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(46.dp).clip(FosaRadius.Control).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){Text(name.take(2).uppercase(),fontWeight=FontWeight.Bold)};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(name,style=MaterialTheme.typography.titleMedium);FosaLabel(role.ifBlank{"MEMBER"})};FosaStatus(if(active)"TALKING" else "LOCAL",true)};FosaMeter(if(active)level else null);content()}}
@Composable fun FosaTalkButton(transmitting:Boolean,enabled:Boolean,onHold:(Boolean)->Unit,modifier:Modifier=Modifier){val haptic=androidx.compose.ui.platform.LocalHapticFeedback.current;val vibrate=LocalFosaHaptic.current
    Box(modifier.sizeIn(minWidth=180.dp,minHeight=180.dp).aspectRatio(1f).clip(CircleShape).background(if(transmitting)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .border(2.dp,if(transmitting)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha=.25f),CircleShape)
        .testTag("talk-button").pointerInput(enabled,vibrate){if(enabled)detectTapGestures(onPress={if(vibrate)haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress);onHold(true);try{tryAwaitRelease()}finally{onHold(false)}})},contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Text(if(transmitting)"●" else "◉",fontSize=26.sp,color=if(transmitting)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary);Text(if(transmitting)"TALKING" else "HOLD TO TALK",fontSize=22.sp,fontWeight=FontWeight.Bold,color=if(transmitting)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface);Text(if(enabled)"Release to listen" else "Microphone / réseau requis",fontSize=12.sp,color=if(transmitting)MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)}}}
