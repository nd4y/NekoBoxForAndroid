package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  String netBirdStatus();
  String netBirdDiagnostics();
  String startNetBirdDiagnostics();
  String cancelNetBirdDiagnostics();

  boolean isCoreProfilingRunning();
  boolean hasCoreProfilerSnapshot();
  void performLibcoreGcSweep();
  void startCoreProfiling();
  void stopCoreProfiling();
  void writeCoreProfilerSnapshot(String outputDir);
  void deleteCoreProfilerSnapshot();
}
