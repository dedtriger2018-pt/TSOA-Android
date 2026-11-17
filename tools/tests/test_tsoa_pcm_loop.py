#!/usr/bin/env python3
"""Exercise the production PCM reader with the actual stereo loop and bad WAV data."""
from pathlib import Path
import subprocess
import tempfile

HARNESS = r'''
package com.winlator.tsoa;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class TsoaPcmLoopTest {
 private static void check(boolean test,String message) { if(!test) throw new AssertionError(message); }
 private static void reject(byte[] data) throws Exception {
  boolean rejected=false;
  try { TsoaPcmLoop.read(new ByteArrayInputStream(data)); } catch(IOException expected) { rejected=true; }
  check(rejected,"bad WAV accepted");
 }
 public static void main(String[] args) throws Exception {
  byte[] wav=Files.readAllBytes(Paths.get(args[0]));
  TsoaPcmLoop loop=TsoaPcmLoop.read(new ByteArrayInputStream(wav));
  check(loop.channels==2 && loop.sampleRate==24000,"stereo/rate changed");
  check(loop.frameCount==720000,"loop points must count stereo audio frames");
  check(loop.pcm.length==2880000,"unexpected decoded memory use");
  check(Arrays.equals(loop.pcm,Arrays.copyOfRange(wav,44,wav.length)),"WAV header entered audio buffer");
  reject(Arrays.copyOf(wav,wav.length-1));
  byte[] bad=wav.clone(); bad[0]='X'; reject(bad);
  bad=wav.clone(); bad[22]=3; reject(bad);
  bad=wav.clone(); bad[34]=24; reject(bad);
  bad=wav.clone(); bad[20]=3; reject(bad);
  bad=wav.clone(); bad[40]=(byte)0xff; bad[41]=(byte)0xff; bad[42]=(byte)0xff; bad[43]=0x7f; reject(bad);
  // Real RIFF files may contain odd-sized metadata chunks before the PCM data.
  ByteArrayOutputStream odd=new ByteArrayOutputStream();
  odd.write(wav,0,36); odd.write("JUNK".getBytes("US-ASCII")); odd.write(new byte[]{1,0,0,0,42,0}); odd.write(wav,36,wav.length-36);
  TsoaPcmLoop decorated=TsoaPcmLoop.read(new ByteArrayInputStream(odd.toByteArray()));
  check(decorated.frameCount==loop.frameCount && Arrays.equals(decorated.pcm,loop.pcm),"metadata padding corrupts PCM loop");
  System.out.println("PASS: actual stereo payload/frame loop length; bounded memory; odd metadata padding; rejection of truncated, non-PCM, wrong channel/bit depth and oversized WAVs.");
 }
}
'''
root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='tsoa-pcm-loop-') as tmp:
 work=Path(tmp); classes=work/'classes'; classes.mkdir()
 harness=work/'TsoaPcmLoopTest.java'; harness.write_text(HARNESS)
 subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-source','8','-target','8','-Xlint:-options','-d',str(classes),str(root/'app/src/main/java/com/winlator/tsoa/TsoaPcmLoop.java'),str(harness)],check=True)
 subprocess.run(['java','-cp',str(classes),'com.winlator.tsoa.TsoaPcmLoopTest',str(root/'app/src/main/assets/tsoa_lobby_rain.wav')],check=True)
