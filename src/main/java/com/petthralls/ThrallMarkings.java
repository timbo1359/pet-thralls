/*
 * Copyright (c) 2026, Ox Timmy
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.petthralls;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recreates the thrall's type markings for a pet.
 * <p>
 * A thrall's model carries both markings itself:
 * <ul>
 * <li>A flat symbol disc at its feet, separate from the body (it shares no vertices with it).</li>
 * <li>An outline: a slightly larger, inside-out copy of the body in one colour per type. Because
 * its faces point inwards, only the parts sticking out past the body's silhouette are drawn.</li>
 * </ul>
 * The symbol is cut out of the thrall's own model; the outline is rebuilt the same way around the pet.
 * <p>
 * Things learnt from the live client: {@link Client#mergeModels} welds together vertices that sit at
 * the same position, so a copy has to be moved before it is merged in or it fuses back into the
 * original; and changes are made through {@link ModelData}'s own methods where possible, with any
 * direct array change read back so a marking is skipped rather than drawn wrong.
 */
final class ThrallMarkings
{
	private static final Logger log = LoggerFactory.getLogger(ThrallMarkings.class);

	// Faces within this distance (model units) of the lowest point count as on the ground
	private static final int GROUND_TOLERANCE = 6;
	// Faces flatter than this (model units, top to bottom) count as lying flat
	private static final int FLAT_TOLERANCE = 2;
	// How far (model units) the symbol is lifted above the ground. In the thralls' models it lies
	// level with or just below the ground, which only the game's own NPC drawing gets away with.
	private static final int SYMBOL_LIFT = 3;
	private static final int DEFAULT_SCALE = 128;
	// When the outline can only be grown from the centre, parts further out than this many
	// average radii drift too far from their outline, so they go without
	private static final double GROWN_REACH = 2;

	private ThrallMarkings()
	{
	}

	/**
	 * Builds the thrall's ground symbol on its own, with the rest of the thrall's faces removed.
	 *
	 * @return the unlit symbol, or null if the model isn't loaded or has no symbol
	 */
	static ModelData groundSymbol(Client client, int thrallNpcId)
	{
		NPCComposition composition = client.getNpcDefinition(thrallNpcId);
		if (composition == null || composition.getModels() == null || composition.getModels().length == 0)
		{
			return null;
		}

		int[] ids = composition.getModels();
		ModelData[] parts = new ModelData[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			parts[i] = client.loadModelData(ids[i]);
			if (parts[i] == null)
			{
				return null;
			}
		}

		// A fresh copy whose arrays are ours to change
		ModelData model = client.mergeModels(parts, parts.length).cloneColors();
		boolean[] symbol = symbolFaces(model);

		int kept = 0;
		int[] i1 = model.getFaceIndices1();
		int[] i2 = model.getFaceIndices2();
		int[] i3 = model.getFaceIndices3();
		int removedFace = -1;
		for (int f = 0; f < model.getFaceCount(); f++)
		{
			if (symbol[f])
			{
				kept++;
			}
			else
			{
				// Collapse the face to a point so it isn't drawn
				i2[f] = i1[f];
				i3[f] = i1[f];
				removedFace = f;
			}
		}

		if (kept == 0)
		{
			return null;
		}
		if (removedFace != -1 && (model.getFaceIndices2()[removedFace] != model.getFaceIndices1()[removedFace]
			|| model.getFaceIndices3()[removedFace] != model.getFaceIndices1()[removedFace]))
		{
			log.debug("Face changes didn't stick; not drawing the thrall symbol");
			return null;
		}

		// Lift it just clear of the ground, so the ground doesn't hide it or flicker through it
		float lowest = -Float.MAX_VALUE;
		float[] y = model.getVerticesY();
		for (int f = 0; f < model.getFaceCount(); f++)
		{
			if (symbol[f])
			{
				lowest = Math.max(lowest, Math.max(y[i1[f]], Math.max(y[i2[f]], y[i3[f]])));
			}
		}
		model.cloneVertices().translate(0, -(int) Math.ceil(lowest) - SYMBOL_LIFT, 0);

		float lifted = -Float.MAX_VALUE;
		float[] liftedY = model.getVerticesY();
		int opaque = 0;
		byte[] transparencies = model.getFaceTransparencies();
		for (int f = 0; f < model.getFaceCount(); f++)
		{
			if (symbol[f])
			{
				lifted = Math.max(lifted, Math.max(liftedY[i1[f]], Math.max(liftedY[i2[f]], liftedY[i3[f]])));
				if (transparencies == null || transparencies[f] == 0)
				{
					opaque++;
				}
			}
		}
		log.debug("Thrall {} symbol: {} of {} faces kept ({} solid), {} vertices, lowest point {} lifted to {}",
			thrallNpcId, kept, model.getFaceCount(), opaque, model.getVerticesCount(), lowest, lifted);
		return model;
	}

	/**
	 * Picks out the ground symbol: faces lying flat at the model's lowest point that
	 * share no vertices with any other face (the feet's soles are also flat at the
	 * ground, but are joined to the legs).
	 */
	static boolean[] symbolFaces(ModelData model)
	{
		int faceCount = model.getFaceCount();
		int vertexCount = model.getVerticesCount();
		float[] y = model.getVerticesY();
		int[] i1 = model.getFaceIndices1();
		int[] i2 = model.getFaceIndices2();
		int[] i3 = model.getFaceIndices3();

		// Model Y points down, so the ground is the largest Y
		float ground = -Float.MAX_VALUE;
		for (int v = 0; v < vertexCount; v++)
		{
			ground = Math.max(ground, y[v]);
		}

		boolean[] flatOnGround = new boolean[faceCount];
		boolean[] usedByOtherFaces = new boolean[vertexCount];
		for (int f = 0; f < faceCount; f++)
		{
			float low = Math.max(y[i1[f]], Math.max(y[i2[f]], y[i3[f]]));
			float high = Math.min(y[i1[f]], Math.min(y[i2[f]], y[i3[f]]));
			flatOnGround[f] = low - high <= FLAT_TOLERANCE && high >= ground - GROUND_TOLERANCE;
			if (!flatOnGround[f])
			{
				usedByOtherFaces[i1[f]] = true;
				usedByOtherFaces[i2[f]] = true;
				usedByOtherFaces[i3[f]] = true;
			}
		}

		boolean[] symbol = new boolean[faceCount];
		for (int f = 0; f < faceCount; f++)
		{
			symbol[f] = flatOnGround[f] && !usedByOtherFaces[i1[f]] && !usedByOtherFaces[i2[f]] && !usedByOtherFaces[i3[f]];
		}
		return symbol;
	}

	/**
	 * Finds the vertices belonging to pieces of the model that are built inside out. Each
	 * connected piece's faces are summed as cones from the piece's centre: the total is
	 * positive when they face outwards (the usual way) and negative when they face inwards.
	 */
	static boolean[] insideOutVertices(float[] x, float[] y, float[] z, int[] i1, int[] i2, int[] i3,
		int vertexCount, int faceCount)
	{
		// Join up vertices that share faces
		int[] parent = new int[vertexCount];
		for (int v = 0; v < vertexCount; v++)
		{
			parent[v] = v;
		}
		for (int f = 0; f < faceCount; f++)
		{
			union(parent, i1[f], i2[f]);
			union(parent, i1[f], i3[f]);
		}

		// Each piece's centre
		double[] cx = new double[vertexCount];
		double[] cy = new double[vertexCount];
		double[] cz = new double[vertexCount];
		int[] count = new int[vertexCount];
		for (int v = 0; v < vertexCount; v++)
		{
			int root = find(parent, v);
			cx[root] += x[v];
			cy[root] += y[v];
			cz[root] += z[v];
			count[root]++;
		}
		for (int v = 0; v < vertexCount; v++)
		{
			if (count[v] > 0)
			{
				cx[v] /= count[v];
				cy[v] /= count[v];
				cz[v] /= count[v];
			}
		}

		// Signed volume of each piece, measured from its centre
		double[] volume = new double[vertexCount];
		for (int f = 0; f < faceCount; f++)
		{
			int root = find(parent, i1[f]);
			double ax = x[i1[f]] - cx[root], ay = y[i1[f]] - cy[root], az = z[i1[f]] - cz[root];
			double bx = x[i2[f]] - cx[root], by = y[i2[f]] - cy[root], bz = z[i2[f]] - cz[root];
			double qx = x[i3[f]] - cx[root], qy = y[i3[f]] - cy[root], qz = z[i3[f]] - cz[root];
			volume[root] += ax * (by * qz - bz * qy) + ay * (bz * qx - bx * qz) + az * (bx * qy - by * qx);
		}

		boolean[] insideOut = new boolean[vertexCount];
		for (int v = 0; v < vertexCount; v++)
		{
			insideOut[v] = volume[find(parent, v)] < 0;
		}
		return insideOut;
	}

	private static int find(int[] parent, int v)
	{
		while (parent[v] != v)
		{
			parent[v] = parent[parent[v]];
			v = parent[v];
		}
		return v;
	}

	private static void union(int[] parent, int a, int b)
	{
		int ra = find(parent, a);
		int rb = find(parent, b);
		if (ra != rb)
		{
			parent[ra] = rb;
		}
	}

	/**
	 * Adds an outline to a model the way thralls have one: an inside-out copy of the
	 * model, slightly bigger, in one colour.
	 * <p>
	 * The copy is grown from the model's centre and recoloured with {@link ModelData}'s own
	 * methods, then merged in after the model (moving it first keeps the merge from welding it
	 * back onto the model). Its faces are then turned inside out. If the client lets vertex
	 * positions be changed directly, the copy is then fitted exactly, each vertex pushed out
	 * along the surface, which gives an even outline on long or thin parts too.
	 *
	 * @param color     Jagex HSL colour of the outline
	 * @param thickness how far out the outline sits, in the model's own (unscaled) units
	 * @return the model with its outline, or null if it couldn't be built
	 */
	static ModelData withOutline(Client client, ModelData model, short color, float thickness)
	{
		int vertexCount = model.getVerticesCount();
		int faceCount = model.getFaceCount();
		if (vertexCount == 0 || faceCount == 0)
		{
			return null;
		}

		// The model's centre and average radius, to grow the copy from
		float[] x = model.getVerticesX();
		float[] y = model.getVerticesY();
		float[] z = model.getVerticesZ();
		double cx = 0;
		double cy = 0;
		double cz = 0;
		for (int v = 0; v < vertexCount; v++)
		{
			cx += x[v];
			cy += y[v];
			cz += z[v];
		}
		cx /= vertexCount;
		cy /= vertexCount;
		cz /= vertexCount;
		double radius = 0;
		for (int v = 0; v < vertexCount; v++)
		{
			radius += Math.sqrt((x[v] - cx) * (x[v] - cx) + (y[v] - cy) * (y[v] - cy) + (z[v] - cz) * (z[v] - cz));
		}
		radius = Math.max(1, radius / vertexCount);

		// 1. The copy: grown a little and all in the outline colour, untextured
		ModelData shell = client.mergeModels(model);
		if (shell == model)
		{
			shell = model.shallowCopy();
		}
		if (shell == model || shell.getFaceCount() != faceCount)
		{
			log.debug("Couldn't copy the model for an outline");
			return null;
		}
		shell = shell.cloneVertices().cloneColors();
		if (shell.getFaceTextures() != null)
		{
			// The client's cloneTextures() fails on models without textures
			shell = shell.cloneTextures();
		}
		int grow = Math.max(1, (int) Math.round(DEFAULT_SCALE * thickness / radius));
		int icx = (int) Math.round(cx);
		int icy = (int) Math.round(cy);
		int icz = (int) Math.round(cz);
		shell.translate(-icx, -icy, -icz);
		shell.scale(DEFAULT_SCALE + grow, DEFAULT_SCALE + grow, DEFAULT_SCALE + grow);
		shell.translate(icx, icy, icz);

		Set<Short> colors = new HashSet<>();
		for (short c : shell.getFaceColors())
		{
			colors.add(c);
		}
		for (short c : colors)
		{
			if (c != color)
			{
				shell.recolor(c, color);
			}
		}
		short[] shellTextures = shell.getFaceTextures();
		if (shellTextures != null)
		{
			Set<Short> textures = new HashSet<>();
			for (short t : shellTextures)
			{
				textures.add(t);
			}
			for (short t : textures)
			{
				if (t != -1)
				{
					shell.retexture(t, (short) -1);
				}
			}
		}

		// 2. The model followed by its copy
		ModelData merged = client.mergeModels(model, shell);
		if (merged.getFaceCount() != faceCount * 2)
		{
			log.debug("Couldn't merge the outline onto the model");
			return null;
		}

		int mergedVertexCount = merged.getVerticesCount();
		float[] mx = merged.getVerticesX();
		float[] my = merged.getVerticesY();
		float[] mz = merged.getVerticesZ();
		int[] i1 = merged.getFaceIndices1();
		int[] i2 = merged.getFaceIndices2();
		int[] i3 = merged.getFaceIndices3();

		// Some pieces of a model are built inside out (their faces point inwards); their outline
		// keeps the piece's own winding instead of being flipped
		boolean[] insideOut = insideOutVertices(mx, my, mz, i1, i2, i3, mergedVertexCount, faceCount);

		// 3. Fit the copy exactly to the surface, if vertex positions can be changed
		boolean fitted = fitToSurface(merged, faceCount, thickness, insideOut);

		// 4. Turn the copy inside out. See-through faces (e.g. crystals) get no outline,
		// as it would show through them and tint them.
		byte[] transparencies = merged.getFaceTransparencies();
		double reach = GROWN_REACH * radius;
		int outlinedFace = -1;
		int outlined = 0;
		for (int f = faceCount; f < faceCount * 2; f++)
		{
			int source = f - faceCount;
			// Growing from the centre slides inside-out and far-out pieces' outlines off them
			boolean tooFar = !fitted && (insideOut[i1[source]]
				|| distance(mx, my, mz, i1[source], cx, cy, cz) > reach
				|| distance(mx, my, mz, i2[source], cx, cy, cz) > reach
				|| distance(mx, my, mz, i3[source], cx, cy, cz) > reach);
			if (tooFar || transparencies != null && transparencies[f] != 0)
			{
				// Collapse the face to a point so it isn't drawn
				i2[f] = i1[f];
				i3[f] = i1[f];
				continue;
			}

			if (!insideOut[i1[f - faceCount]])
			{
				int swap = i2[f];
				i2[f] = i3[f];
				i3[f] = swap;
			}
			outlinedFace = f;
			outlined++;
		}

		if (outlinedFace == -1)
		{
			// Nothing solid to outline
			return null;
		}
		if (merged.getFaceIndices2()[outlinedFace] != i2[outlinedFace]
			|| merged.getFaceIndices3()[outlinedFace] != i3[outlinedFace])
		{
			log.debug("Face changes didn't stick; not drawing the outline");
			return null;
		}

		log.debug("Outline of {} faces, {}", outlined, fitted ? "fitted to the surface" : "grown from the centre");
		return merged;
	}

	private static double distance(float[] x, float[] y, float[] z, int v, double cx, double cy, double cz)
	{
		double dx = x[v] - cx;
		double dy = y[v] - cy;
		double dz = z[v] - cz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Moves each vertex of the copy (the second half of the model's faces) to its original
	 * vertex pushed out along the surface by {@code thickness}.
	 *
	 * @return false if vertex positions can't be changed directly, leaving the copy as it was
	 */
	private static boolean fitToSurface(ModelData merged, int faceCount, float thickness, boolean[] insideOut)
	{
		int vertexCount = merged.getVerticesCount();
		float[] x = merged.getVerticesX();
		float[] y = merged.getVerticesY();
		float[] z = merged.getVerticesZ();
		int[] i1 = merged.getFaceIndices1();
		int[] i2 = merged.getFaceIndices2();
		int[] i3 = merged.getFaceIndices3();

		// Do changes to the vertex arrays reach the model?
		int probe = i1[faceCount];
		float before = x[probe];
		x[probe] = before + 1;
		boolean live = merged.getVerticesX()[probe] == before + 1;
		x[probe] = before;
		if (!live)
		{
			return false;
		}

		// Vertices of the model itself (any the merge shared with the copy are left alone)
		boolean[] original = new boolean[vertexCount];
		float[] nx = new float[vertexCount];
		float[] ny = new float[vertexCount];
		float[] nz = new float[vertexCount];
		for (int f = 0; f < faceCount; f++)
		{
			int a = i1[f];
			int b = i2[f];
			int c = i3[f];
			original[a] = original[b] = original[c] = true;
			float ux = x[b] - x[a];
			float uy = y[b] - y[a];
			float uz = z[b] - z[a];
			float vx = x[c] - x[a];
			float vy = y[c] - y[a];
			float vz = z[c] - z[a];
			float fx = uy * vz - uz * vy;
			float fy = uz * vx - ux * vz;
			float fz = ux * vy - uy * vx;
			for (int v : new int[]{a, b, c})
			{
				nx[v] += fx;
				ny[v] += fy;
				nz[v] += fz;
			}
		}

		// Each copy vertex takes its original's position, pushed out
		int[][] corners = {i1, i2, i3};
		for (int f = 0; f < faceCount; f++)
		{
			for (int[] corner : corners)
			{
				int source = corner[f];
				int copy = corner[faceCount + f];
				if (original[copy])
				{
					continue;
				}
				float length = (float) Math.sqrt(nx[source] * nx[source] + ny[source] * ny[source] + nz[source] * nz[source]);
				float offset = length == 0 ? 0 : (insideOut[source] ? -thickness : thickness) / length;
				x[copy] = x[source] + nx[source] * offset;
				y[copy] = y[source] + ny[source] * offset;
				z[copy] = z[source] + nz[source] * offset;
			}
		}
		return true;
	}
}
