package com.soubhagya.systivex.intelligence;

import com.soubhagya.systivex.twin.model.SystemEntityType;
import java.util.Set;

public record SimulationSettings(int maxDepth, Set<SystemEntityType> includeTypes, int rootCount) {}
