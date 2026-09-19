# Build & Execution Instructions

## 1. Building the Core Shared C++ Engine & Tests
Prerequisites: CMake 3.15+, C++20 compiler (Clang / GCC / MSVC).

```bash
cd core
mkdir build && cd build
cmake ..
cmake --build .
ctest --output-on-failure
# Or run the test binary directly:
./test_core_engine
```

## 2. Running the Mobile Application
Prerequisites: Node.js 18+, React Native CLI, Android SDK / Xcode.

```bash
cd mobile
npm install

# Run Android
npm run android

# Run iOS
cd ios && pod install && cd ..
npm run ios
```
