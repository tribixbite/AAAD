# The service name is written into cloned manifests by patch_manifest.py. Everything reachable
# from it may still be optimized and shrunk, but the entry point itself must keep this name.
-keep class com.legs.appsforaa.carify.CarifyCarAppService { *; }

# Preserve protocol annotations and generic signatures; the Car App Library's own consumer rules
# keep its Binder interfaces and serialized fields while allowing unused templates to be removed.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod,Signature
