# WifiDoctor

Android app that diagnoses home Wi-Fi problems layer by layer (link → gateway → internet → DNS → throughput → congestion).

_Work in progress — full documentation follows once the implementation lands._

## Module / package structure

```
:core  (pure Kotlin/JVM library — no Android dependencies)
  com.example.wifidoctor.core
    model/       Measurements, LinkInfo, ProbeStats, Diagnosis, Severity, Layer …
    wifi/        WifiMath (band/channel/overlap), congestion analysis, expected link speeds
    probe/       ProbeMath, TCP-connect prober, ping prober + parser, DNS timer, throughput tester
    diagnosis/   Diagnoser + one class per rule
    engine/      DiagnosticEngine (ordered layers, progress events), WalkTester
    history/     Drop detection, room ranking, CSV export

:app   (Android application, package com.example.wifidoctor)
    platform/    WifiManager/ConnectivityManager adapters, network binding, environment monitor
    data/        Room database, repository, DataStore settings
    work/        WorkManager scheduled checks + notifications
    di/          Hilt modules
    ui/          Compose screens (dashboard, rooms, history, settings, permissions), theme, components
```
