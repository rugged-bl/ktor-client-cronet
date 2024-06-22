# ktor-client-cronet

This is a Ktor client engine that uses Cronet as the underlying HTTP client.

Implementation is simplified and **not** tested in production.
Use at your own risk.
Inspired by [cronet-engine](https://github.com/niusounds/cronet-engine)

See example usage in [NetworkFactory.kt](app/src/main/java/com/github/ruggedbl/ktor/client/cronet/NetworkFactory.kt).

## Useful links
- [Cronet / Send a simple request](https://developer.android.com/develop/connectivity/cronet/start)
- [Ktor](https://github.com/ktorio/ktor)
- [Cronet sources](https://source.chromium.org/chromium/chromium/src/+/main:components/cronet/android/java/src/org/chromium/net/impl/CronetEngineBuilderImpl.java)
