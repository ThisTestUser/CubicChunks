/*
 *  This file is part of Cubic Chunks Mod, licensed under the MIT License (MIT).
 *
 *  Copyright (c) 2015 contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */
package cubicchunks.lighting;

import cubicchunks.CubicChunks;
import cubicchunks.util.Coords;
import cubicchunks.world.ICubicWorld;
import cubicchunks.world.column.Column;
import cubicchunks.world.cube.Cube;
import cubicchunks.worldgen.GeneratorStage;

import java.util.Set;

public class LightingManager {

	private static final int TickBudget = 10; // ms. Only 50 ms in a tick @ 20 tps

	private ICubicWorld world;

	private SkyLightCubeDiffuseProcessor skylightCubeDiffuseProcessor;

	private FirstLightProcessor firstLightProcessor;

	public LightingManager(ICubicWorld world) {
		this.world = world;

		this.skylightCubeDiffuseProcessor = new SkyLightCubeDiffuseProcessor(world, "Sky Light Diffuse", 5);

		this.firstLightProcessor = new FirstLightProcessor(GeneratorStage.LIGHTING, "First Light", world.getCubeCache(), 1);
	}

	public void columnSkylightUpdate(UpdateType type, Column column, int localX, int minY, int maxY, int localZ) {
		int blockX = Coords.localToBlock(column.getX(), localX);
		int blockZ = Coords.localToBlock(column.getZ(), localZ);
		switch (type) {
			case IMMEDIATE:
				Set<Integer> toDiffuse = SkyLightUpdateCubeSelector.getCubesY(column, localX, localZ, minY, maxY);
				for (int cubeY : toDiffuse) {
					boolean success = SkyLightCubeDiffuseCalculator.calculate(column, localX, localZ, cubeY);
					if (!success) {
//						CubicChunks.LOGGER.warn("Diffuse lighting update at ({}, {}/{}, {}): needed cubes not loaded. Adding to queue.",
//								Coords.localToBlock(column.getX(), localX),
//								Coords.cubeToMinBlock(cubeY), Coords.cubeToMaxBlock(cubeY),
//								Coords.localToBlock(column.getZ(), localZ));
						queueDiffuseUpdate(column.getCube(cubeY), blockX, blockZ, minY, maxY);
					}
				}
				break;
			case QUEUED:
				toDiffuse = SkyLightUpdateCubeSelector.getCubesY(column, localX, localZ, minY, maxY);
				for (int cubeY : toDiffuse) {
					queueDiffuseUpdate(column.getCube(cubeY), blockX, blockZ, minY, maxY);
				}
				break;
		}
	}

	public void tick() {
		long timeStart = System.currentTimeMillis();
		long timeStop = timeStart + TickBudget;

		// process the queues
		int numProcessed = 0;
		//this.world.profiler.addSection("skyLightOcclusion");
		numProcessed += this.skylightCubeDiffuseProcessor.processQueueUntil(timeStop);
		//this.world.profiler.startSection("firstLight");
		numProcessed += this.firstLightProcessor.processQueueUntil(timeStop);
		//this.world.profiler.endSection();

		// reporting
		long timeDiff = System.currentTimeMillis() - timeStart;
		if (numProcessed > 0) {
			CubicChunks.LOGGER.info(String.format("%s Lighting manager processed %d calculations in %d ms.",
					this.world.isRemote() ? "CLIENT" : "SERVER", numProcessed, timeDiff));
			CubicChunks.LOGGER.info(this.skylightCubeDiffuseProcessor.getProcessingReport());
			CubicChunks.LOGGER.info(this.firstLightProcessor.getProcessingReport());
		}
	}

	public void queueDiffuseUpdate(Cube cube, int blockX, int blockZ, int minY, int maxY) {

		Cube.LightUpdateData data = cube.getLightUpdateData();
		data.queueLightUpdate(Coords.blockToLocal(blockX), Coords.blockToLocal(blockZ), minY, maxY);
		skylightCubeDiffuseProcessor.add(cube.getAddress());
	}

	public void queueFirstLightCalculation(long cubeAddress) {
		this.firstLightProcessor.add(cubeAddress);
	}

	public enum UpdateType {
		IMMEDIATE, QUEUED
	}
}
