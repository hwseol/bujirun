# Add project specific ProGuard rules here.

# Google sign-in (Credential Manager). The Play services provider is found by reflection and hands
# the chosen account back through callbacks R8 can't see: stripped, the account sheet just closed
# with no sign-in in the Play build (androidx.credentials setup docs ask for the same rule).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
