package com.audioviz.patterns.impl;

import com.audioviz.patterns.*;
import java.util.*;

public class RotatingCube extends VisualizationPattern {
    private double rotX = 0, rotY = 0;

    public RotatingCube() {
        super("cube", "Rotating Cube", "3D cube that rotates with audio");
    }

    @Override
    public List<EntityUpdate> calculate(AudioState audio) {
        List<EntityUpdate> entities = new ArrayList<>();
        int count = config.getEntityCount();
        rotY += 0.02 + audio.getAmplitude() * 0.05;
        rotX += 0.01;

        int perEdge = Math.max(2, (int) Math.cbrt(count));
        int idx = 0;

        for (int xi = 0; xi < perEdge && idx < count; xi++) {
            for (int yi = 0; yi < perEdge && idx < count; yi++) {
                for (int zi = 0; zi < perEdge && idx < count; zi++) {
                    double lx = (xi - (perEdge - 1) / 2.0) / perEdge * 0.5;
                    double ly = (yi - (perEdge - 1) / 2.0) / perEdge * 0.5;
                    double lz = (zi - (perEdge - 1) / 2.0) / perEdge * 0.5;

                    // Rotate around Y
                    double cosY = Math.cos(rotY), sinY = Math.sin(rotY);
                    double rx = lx * cosY - lz * sinY;
                    double rz = lx * sinY + lz * cosY;

                    // Rotate around X
                    double cosX = Math.cos(rotX), sinX = Math.sin(rotX);
                    double ry = ly * cosX - rz * sinX;
                    rz = ly * sinX + rz * cosX;

                    entities.add(EntityUpdate.builder()
                        .id(idx).position(0.5 + rx, 0.5 + ry, 0.5 + rz)
                        .scale(calculateScale(audio, idx % 6))
                        .visible(true).band(idx % 6).build());
                    idx++;
                }
            }
        }

        while (idx < count) {
            entities.add(EntityUpdate.builder()
                .id(idx).position(0.5, 0.5, 0.5)
                .scale(0.01).visible(false).band(0).build());
            idx++;
        }
        return entities;
    }

    @Override
    public void reset() { rotX = 0; rotY = 0; }
}
