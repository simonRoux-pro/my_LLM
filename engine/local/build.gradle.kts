plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "pro.simonroux.myllm.engine.local"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // Only the phone's own ABI is shipped. Building the other three would
        // quadruple the build time and triple the APK for no benefit.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        /**
         * The native side is built optimised even in debug.
         *
         * AGP picks CMAKE_BUILD_TYPE from isJniDebuggable, so a debug variant
         * otherwise compiles ggml with -O0 and full symbols. That is the wrong
         * trade twice over: it made the APK 108 MB, and an unoptimised matmul
         * kernel is several times slower, which on this app means the local
         * model looks broken rather than merely undebuggable.
         *
         * Setting the build type on the command line as well is belt and
         * braces: the last -D on a CMake invocation wins, whatever AGP injected.
         */
        debug {
            isJniDebuggable = false
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }

        release {
            isMinifyEnabled = false
            externalNativeBuild {
                cmake {
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
    }

    packaging {
        jniLibs {
            // The .so is loaded with System.loadLibrary, so it must stay a real
            // file in the APK rather than being served from the compressed zip.
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":engine:api"))
    implementation(libs.kotlinx.coroutines.android)
}
