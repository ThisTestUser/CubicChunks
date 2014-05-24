/*******************************************************************************
 * Copyright (c) 2014 Jeff Martin.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Jeff Martin - initial API and implementation
 ******************************************************************************/
package cuchaz.cubicChunks.generator;

import java.util.Random;

import libnoiseforjava.exception.ExceptionInvalidParam;
import net.minecraft.init.Blocks;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldType;
import cuchaz.cubicChunks.generator.biome.biomegen.CubeBiomeGenBase;
import cuchaz.cubicChunks.generator.builder.BasicBuilder;
import cuchaz.cubicChunks.server.CubeWorldServer;
import cuchaz.cubicChunks.util.Coords;
import cuchaz.cubicChunks.util.CubeProcessor;
import cuchaz.cubicChunks.world.Cube;

public class TerrainProcessor extends CubeProcessor
{
	private CubeWorldServer m_worldServer;
	private CubeBiomeGenBase[] m_biomes;
	
	private Random m_rand;
	
	private double[] m_filter5x5;
	private final static double filterTweaker = 15.4412439587182; 
	
	private int xNoiseSize = 5;
	private int yNoiseSize = 3;
	private int zNoiseSize = 5;
	
	private double[][][] noiseArray;
	private double[][][] valueArray;
	
	private BasicBuilder builder;
	private int seaLevel;
	private double maxElev;
	
	public TerrainProcessor( String name, CubeWorldServer worldServer, int batchSize )
	{
		super( name, worldServer.getCubeProvider(), batchSize );
		
		m_worldServer = worldServer;
		m_biomes = null;
		
		m_rand = new Random( m_worldServer.getSeed() );
		
		seaLevel = worldServer.getCubeWorldProvider().getSeaLevel();
		maxElev = 200; // NOTE: this is apparently multiplied by 100
		
		// init the 5x5 filter
		m_filter5x5 = new double[25];

		for( int i=-2; i<=2; i++ )
		{
			for( int j=-2; j<=2; j++ )
			{
				m_filter5x5[i + 2 + ( j + 2 )*5] = (1.0F/MathHelper.sqrt_float( 0.2F + ( i*i + j*j ) ) / filterTweaker);
			}
		}
		
		builder = new BasicBuilder();
		builder.setSeed(m_rand.nextInt());
		builder.setMaxElev(maxElev);
		builder.setSeaLevel(seaLevel);
		try
		{
			builder.build();
		}
		catch( ExceptionInvalidParam ex )
		{
			// this should really be an illegal argument exception,
			// but apparently the author of libnoiseforjava doesn't follow conventions
			throw new IllegalArgumentException( ex );
		}
	}
	
	@Override
	public boolean calculate( Cube cube )
	{
		// init random
		m_rand.setSeed( (long)cube.getX()*341873128712L + (long)cube.getZ()*132897987541L );
		
		// get more biome data
		// NOTE: this is different from the column biome data for some reason...
		// Nick: This is a 10x10 array of biomes, centered on the xz center. points in the array are separated by 4 blocks.
		m_biomes = (CubeBiomeGenBase[])m_worldServer.getCubeWorldProvider().getWorldColumnMananger().getBiomesForGeneration(
			m_biomes,
			cube.getX()*4 - 2, cube.getZ()*4 - 2,
			10, 10
		);
		
		generateTerrain( cube );
		
		return true;
	}
	
	protected void generateTerrain( Cube cube )
	{		
		int cubeX = cube.getX();
		int cubeY = cube.getY();
		int cubeZ = cube.getZ();
		
		noiseArray = getNoiseArray(cubeX, cubeY, cubeZ);
		noiseArray = modifyNoiseArray(noiseArray, cubeX, cubeY, cubeZ);
		noiseArray = scaleNoiseArray(noiseArray, maxElev);
		valueArray = expandNoiseArray(noiseArray);
		
		for( int xRel=0; xRel < 16; xRel++ )
		{
			for( int zRel=0; zRel < 16; zRel++ )
			{				
				for( int yRel=0; yRel < 16; yRel++ )
				{
					double val = valueArray[xRel][yRel][zRel];
					int yAbs = Coords.localToBlock( cubeY, yRel );
					if( val - yAbs > 0 )
					{
						cube.setBlockForGeneration( xRel, yRel, zRel, Blocks.stone );
					}
					else if( yAbs < seaLevel )
					{
						cube.setBlockForGeneration( xRel, yRel, zRel, Blocks.water );
					}
					else
					{
						cube.setBlockForGeneration( xRel, yRel, zRel, Blocks.air );
					}
				}
			}
		}
	}
	
	private double[][][] getNoiseArray(int cubeX, int cubeY, int cubeZ)
	{		
		int cubeXMin = cubeX * xNoiseSize;
		int cubeYMin = cubeY * yNoiseSize;
		int cubeZMin = cubeZ * zNoiseSize;
		
		double[][][] noiseArray = new double[xNoiseSize][yNoiseSize][zNoiseSize];
		
		for (int x = 0; x < xNoiseSize; x++)
		{
			int xPos = cubeXMin + x;
			
			for (int y = 0; y < yNoiseSize; y++)
			{
				int yPos = cubeYMin + y;
				
				for (int z = 0; z < zNoiseSize; z++)
				{
					int zPos = cubeZMin + z;
					
					noiseArray[x][y][z] = builder.getValue(xPos, yPos, zPos);					
				}
			}
		}
		
		return noiseArray;
	}
	
	/**
	 * modify the noise array by applying the biome heights to it.
	 * 
	 * @param noiseArray
	 * @param cubeX
	 * @param cubeY
	 * @param cubeZ
	 * @return
	 */
	private double[][][] modifyNoiseArray(double[][][] noiseArray, int cubeX, int cubeY, int cubeZ)
	{	
		double[][][] modifiedArray = new double[xNoiseSize][yNoiseSize][zNoiseSize];
		
		double maxVal = -90000;
		double minVal = 90000;
		
		for( int x=0; x < xNoiseSize; x++ ) // x= 0..4 (5)
		{
			for( int z=0; z < zNoiseSize; z++ ) // z = 0..4 (5)
			{
				//get the biome at x+2, z+2 from a 10x10 array. 
				//This retrieves a inner 5x5 square of biomes. probably the array towards the bottom left.
				// ie x = 2..6, z= 2..6. 0,1,7,8,9 are not selected.
				CubeBiomeGenBase biome = m_biomes[x + 2 + ( z + 2 )*10]; 
				
				// compute biome height info
				float maxHeightConditioned = 0;
				float minHeightConditioned = 0;
				float sumHeightScale = 0;
				
				double midHeightConditioned = 0;
				double scaleHeightConditioned = 0;
				
				double heightFilter = 0.0;
				
				// apply 5x5 filter to smooth the height scale based on the surrounding biome values. 
				// This actually reaches 8 blocks in each direction and smooths.
				for( int i=-2; i<=2; i++ )
				{
					for( int j=-2; j<=2; j++ )
					{
						// retrieve the biomes in a 5x5 (16x16) square around the x,z coord
						// that actually makes use of a 9x9 area out of the full 10x10 area.
						//ie x = 0..8, z = 0..8 out of 0-9
						CubeBiomeGenBase ijBiome = m_biomes[x + i + 2 + ( z + j + 2 )*10]; 
						
						// ranges from -1.0 to 1.0
						float minHeight = ijBiome.minHeight; // min biome height at the x + i, z + j coord
						float maxHeight = ijBiome.maxHeight; // max biome height at the x + i, z + j coord.
						
						// if world type is amplified, increase the land height a lot. don't change the sea depth.
						if( m_worldServer.getWorldInfo().getTerrainType() == WorldType.field_151360_e && minHeight > 0.0F )
						{
							minHeight = minHeight*2 + 1;
							maxHeight = maxHeight*4 + 1;
						}
						
						// get the height filter value at i,j
						heightFilter = m_filter5x5[i + 2 + ( j + 2 )*5];
						
						// if the biome minheight at x + i, z + j coord is greater than the biome 
						//minheight at x, z, cut the scale in half to smooth
						if( ijBiome.minHeight > biome.minHeight ) 
						{
							heightFilter /= 2;
						}
						
						maxHeightConditioned += maxHeight * heightFilter; // smooth between the min heights and add
						minHeightConditioned += minHeight * heightFilter; // smooth between the max heights and add
						sumHeightScale += heightFilter; // add the height scale from each iteration into a total
					}
				}
				
//				System.out.println("maxHeightConditioned: " + maxHeightConditioned);
//				System.out.println("minHeightConditioned: " + minHeightConditioned);
				
				maxHeightConditioned /= sumHeightScale;
				minHeightConditioned /= sumHeightScale;
				
				maxHeightConditioned = maxHeightConditioned * 0.9F + 0.1F;
				minHeightConditioned = ( minHeightConditioned * 4.0F - 1.0F ) / 8.0F;
				
				// get the midpoint of the conditioned height. bias the noise value with this.
				midHeightConditioned = lerp( 0.5, minHeightConditioned, maxHeightConditioned);
				//get the range of the conditioned heights. scale the noise value with this.
				scaleHeightConditioned = (maxHeightConditioned - minHeightConditioned) / 2;
					
				for( int y=0; y<=2; y++ )
				{
//					if( noiseOffset < 0.0D )
//					{
//						noiseOffset *= 4.0D;
//					}
//					
//					double lowNoise = m_terrainNoiseLow[noiseIndex] / 512.0D;
//					double highNoise = m_terrainNoiseHigh[noiseIndex] / 512.0D;
//					double tNoise = ( m_terrainNoiseT[noiseIndex] / 10.0D + 1.0D ) / 2.0D;
//					m_terrainNoise[noiseIndex++] = MathHelper.denormalizeClamp( lowNoise, highNoise, tNoise ) - noiseOffset;
					
					// get the value from the array
					double tempVal = noiseArray[x][y][z]; 
					
					//bias the value
					tempVal += midHeightConditioned; 
					
					// scale the value
					tempVal *= Math.abs(scaleHeightConditioned); 
					
					if(tempVal < minVal)
					{
						minVal = tempVal;
					}
					
					if (tempVal > maxVal)
					{
						maxVal = tempVal;
					}
					
					//store the scaled offset value in the modified array
//					modifiedArray[x][y][z] = tempVal - noiseOffset;
					
					modifiedArray[x][y][z] = tempVal;
					
				} // end y
			} // end z
		} // end x
		
		System.out.println(cubeX + ":" + cubeY + ":" + cubeZ);
		System.out.println("minVal: " + minVal);
		System.out.println("maxVal: " + maxVal);
		
		return modifiedArray;
	}
	
	private double[][][] scaleNoiseArray(double[][][] noiseArray, double maxElev)
	{
		//fudge factor so maxElev can be actual block height
		maxElev *= 100;
		
		for(int x = 0; x < xNoiseSize; x++)
		{
			for (int z = 0; z < zNoiseSize; z++)
			{
				for (int y = 0; y < yNoiseSize; y++)
				{
					noiseArray[x][y][z] *= maxElev;
				}
			}
		}
		
		return noiseArray;
	}
	
	/**
	 * expand the noise array to 16x16x16 by interpolating the values.
	 * 
	 * @param noiseArray
	 * @return
	 */
	private double[][][] expandNoiseArray(double[][][] noiseArray)
	{	
		int xSteps = 16 / (xNoiseSize - 1);
		int ySteps = 16 / (yNoiseSize - 1);
		int zSteps = 16 / (zNoiseSize - 1);
		
		// create the expanded array for the interpolated values
		double[][][] expandedArray = new double[16][16][16];
		
		// use the noise to generate the terrain
		for( int noiseX=0; noiseX < xNoiseSize - 1; noiseX++ )
		{
			for( int noiseZ=0; noiseZ < zNoiseSize - 1; noiseZ++ )
			{
				for( int noiseY=0; noiseY < yNoiseSize - 1; noiseY++ )
				{
					// get the noise samples
					
					double x0y0z0 = noiseArray[noiseX][noiseY][noiseZ];
					double x0y0z1 = noiseArray[noiseX][noiseY][noiseZ + 1];
					double x1y0z0 = noiseArray[noiseX + 1][noiseY][noiseZ];
					double x1y0z1 = noiseArray[noiseX + 1][noiseY][noiseZ + 1];
					
					double x0y1z0 = noiseArray[noiseX][noiseY + 1][noiseZ];
					double x0y1z1 = noiseArray[noiseX][noiseY + 1][noiseZ + 1];
					double x1y1z0 = noiseArray[noiseX + 1][noiseY + 1][noiseZ];
					double x1y1z1 = noiseArray[noiseX + 1][noiseY + 1][noiseZ + 1];
					
//					System.out.println("Processing a minicube! " + noiseX + ":" + noiseY + ":" + noiseZ);
//					System.out.println("x0y0z0: " + x0y0z0);
//					System.out.println("x0y0z1: " + x0y0z1);
//					System.out.println("x1y0z0: " + x1y0z0);
//					System.out.println("x1y0z1: " + x1y0z1);
//					
//					System.out.println("x0y1z0: " + x0y1z0);
//					System.out.println("x0y1z1: " + x0y1z1);
//					System.out.println("x1y1z0: " + x1y1z0);
//					System.out.println("x1y1z1: " + x1y1z1);		
					
					for (int x = 0; x < xSteps; x++)
					{
						int xRel = noiseX * xSteps + x;
						
						double xd = (double) x / xSteps;
						
						//interpolate along x
						double xy0z0 = lerp(xd, x0y0z0, x1y0z0);
						double xy0z1 = lerp(xd, x0y0z1, x1y0z1);
						double xy1z0 = lerp(xd, x0y1z0, x1y1z0);
						double xy1z1 = lerp(xd, x0y1z1, x1y1z1);
						
//						System.out.println("xd: " + xd);
//						System.out.println("xy0z0: " + xy0z0);
//						System.out.println("xy0z1: " + xy0z1);
//						System.out.println("xy1z0: " + xy1z0);
//						System.out.println("xy1z1: " + xy1z1);
						
						for (int z = 0; z < zSteps; z++)
						{
							int zRel = noiseZ * zSteps + z;
							
							double zd = (double) z / zSteps;
							
							//interpolate along z
							double xy0z = lerp(zd, xy0z0, xy0z1);
							double xy1z = lerp(zd, xy1z0, xy1z1);
							
//							System.out.println("zd: " + zd);
//							System.out.println("xy0z: " + xy0z);
//							System.out.println("xy1z: " + xy1z);
							
							for (int y = 0; y < ySteps; y++)
							{
								int yRel = noiseY * ySteps + y;
								
								double yd = (double) y / ySteps;
								
								// interpolate along y
								double xyz = lerp(yd, xy0z, xy1z);
								
//								System.out.println("yd: " + yd);
//								System.out.println("xyz: " + xyz);
								
								expandedArray[xRel][yRel][zRel] = xyz;
							}
						}
					}
//					System.out.println("end minicube processing!");	
				}
			}
		}
		
		return expandedArray;	
	}
	
	private double lerp(double a, double min, double max)
	{
		return min + a * (max - min);
	}
}