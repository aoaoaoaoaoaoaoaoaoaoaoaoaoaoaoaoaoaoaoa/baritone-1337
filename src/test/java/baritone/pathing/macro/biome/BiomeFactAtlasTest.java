package baritone.pathing.macro.biome;

import baritone.pathing.macro.core.MacroCellEvidence;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

public class BiomeFactAtlasTest {
  @Test
  public void factAtlasPreservesEvidenceChannel() {
    BiomeFactAtlas predicted = BiomeFactAtlas.parse(JsonParser.parseString("""
      {
        "cellBlocks": 16,
        "evidence": "predicted",
        "cells": [[2, -3, "minecraft:plains", 64]]
      }
      """).getAsJsonObject());
    assertEquals(MacroCellEvidence.PREDICTED, predicted.cell(2, -3).orElseThrow().evidence());
    assertTrue(predicted.cell(2, -3).orElseThrow().factual());

    BiomeFactAtlas mixed = BiomeFactAtlas.parse(JsonParser.parseString("""
      {
        "cellBlocks": 16,
        "evidence": "cached",
        "chunks": [
          {"cx": 0, "cz": 0, "biome": "minecraft:forest", "surfaceY": 72},
          {"cx": 1, "cz": 0, "biome": "minecraft:plains", "surfaceY": 64, "evidence": "predicted"}
        ]
      }
      """).getAsJsonObject());
    assertEquals(MacroCellEvidence.CACHED, mixed.cell(0, 0).orElseThrow().evidence());
    assertEquals(MacroCellEvidence.PREDICTED, mixed.cell(1, 0).orElseThrow().evidence());
  }
}
