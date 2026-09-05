#include <jni.h>
#include <cstdlib>
#include <string>
#include <vector>

// Compile the supplied renderer without modifying its source. Its main() is
// renamed only in this translation unit so the JNI shared library can invoke
// the exact same renderer entry point.
#define main novela_renderer_main
#include "renderer/novela_cinematic_renderer.cpp"
#undef main

extern "C" JNIEXPORT jint JNICALL
Java_my_noveldokusha_cinematic_1video_CinematicVideoNative_renderNative(
    JNIEnv* env,
    jclass,
    jstring audioPath,
    jstring timelinePath,
    jstring outputPath,
    jstring ffmpegDirectory,
    jstring encoder,
    jstring preset) {
    if (!audioPath || !timelinePath || !outputPath || !ffmpegDirectory || !encoder || !preset) {
        return 2;
    }

    const char* audio = env->GetStringUTFChars(audioPath, nullptr);
    const char* timeline = env->GetStringUTFChars(timelinePath, nullptr);
    const char* output = env->GetStringUTFChars(outputPath, nullptr);
    const char* ffmpegDir = env->GetStringUTFChars(ffmpegDirectory, nullptr);
    const char* encoderValue = env->GetStringUTFChars(encoder, nullptr);
    const char* presetValue = env->GetStringUTFChars(preset, nullptr);

    if (!audio || !timeline || !output || !ffmpegDir || !encoderValue || !presetValue) {
        if (audio) env->ReleaseStringUTFChars(audioPath, audio);
        if (timeline) env->ReleaseStringUTFChars(timelinePath, timeline);
        if (output) env->ReleaseStringUTFChars(outputPath, output);
        if (ffmpegDir) env->ReleaseStringUTFChars(ffmpegDirectory, ffmpegDir);
        if (encoderValue) env->ReleaseStringUTFChars(encoder, encoderValue);
        if (presetValue) env->ReleaseStringUTFChars(preset, presetValue);
        return 2;
    }

    const char* oldPath = std::getenv("PATH");
    const std::string path = std::string(ffmpegDir) +
        (oldPath && *oldPath ? std::string(":") + oldPath : std::string());
    setenv("PATH", path.c_str(), 1);

    std::vector<std::string> args;
    args.emplace_back("novela_cpp_renderer");
    args.emplace_back("--audio");
    args.emplace_back(audio);
    args.emplace_back("--timeline");
    args.emplace_back(timeline);
    args.emplace_back("--output");
    args.emplace_back(output);
    args.emplace_back("--encoder");
    args.emplace_back(encoderValue);
    args.emplace_back("--preset");
    args.emplace_back(presetValue);

    std::vector<char*> argv;
    argv.reserve(args.size() + 1);
    for (auto& arg : args) argv.push_back(arg.data());
    argv.push_back(nullptr);

    const jint result = novela_renderer_main(static_cast<int>(args.size()), argv.data());

    env->ReleaseStringUTFChars(audioPath, audio);
    env->ReleaseStringUTFChars(timelinePath, timeline);
    env->ReleaseStringUTFChars(outputPath, output);
    env->ReleaseStringUTFChars(ffmpegDirectory, ffmpegDir);
    env->ReleaseStringUTFChars(encoder, encoderValue);
    env->ReleaseStringUTFChars(preset, presetValue);
    return result;
}
