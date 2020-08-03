# hono-thresh
Eclipse Hono subscriber client for anomaly detection demo

## 1. Overview

Run multiple sensors, eg. simulated by
[MIMIC MQTT Lab Hono](https://mqttlab.iotsim.io/hono)
from
[Gambit Communications](https://www.gambitcomm.com)
publishing telemetry. This client detects when any of them exceeds a threshold.


<IMG src=hono-cli-anomalies-detected.png width=200>

## 2. Build

Clone the
[Eclipse Hono Git source](https://github.com/eclipse/hono)
and build hono-cli.jar according to their instructions.

Then take the sources from here and apply them to that source tree.

