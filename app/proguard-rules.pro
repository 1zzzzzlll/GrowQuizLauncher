-repackageclasses ''
-allowaccessmodification

# The bundled speech engine finds its Java classes by name from native code (JNI: package, class and
# method names are part of the ABI). `-repackageclasses` above would move them out of their package and
# every model load would fail with "Invalid OfflineTtsConfig". Keep the whole package untouched.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
