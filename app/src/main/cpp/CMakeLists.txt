cmake_minimum_required(VERSION 3.22.1)
project(icecamv2_native LANGUAGES CXX)

# libvc.so is packaged from reference APK (full hook/inject implementation).
# ShadowHook 2.x still comes from Maven prefab (libshadowhook.so).
# vcplax daemon is packaged from reference APK.
# Sources under libvc/ and vcplax/ remain for future AOSP builds.
