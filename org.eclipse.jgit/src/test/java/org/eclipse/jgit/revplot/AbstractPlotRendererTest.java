/*
 * Copyright (C) 2014 Rüdiger Herrmann <ruediger.herrmann@gmx.de>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.eclipse.jgit.revplot;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.junit.Before;

public class AbstractPlotRendererTest extends RepositoryTestCase {

	private Git git;
	private TestPlotRenderer plotRenderer;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		git = new Git(db);
		plotRenderer = new TestPlotRenderer();
	}

	private PlotCommitList<PlotLane> createCommitList(ObjectId start)
			throws IOException {
		TestPlotWalk walk = new TestPlotWalk(db);
		walk.markStart(walk.parseCommit(start));
		PlotCommitList<PlotLane> commitList = new PlotCommitList<PlotLane>();
		commitList.source(walk);
		commitList.fillTo(1000);
		return commitList;
	}

	private static class TestPlotWalk extends PlotWalk {
		public TestPlotWalk(Repository repo) {
			super(repo);
		}
	}

	private static class TestPlotRenderer extends
			AbstractPlotRenderer<PlotLane, Object> {

		List<Integer> indentations = new LinkedList<Integer>();

		@Override
		protected int drawLabel(int x, int y, Ref ref) {
			return 0;
		}

		@Override
		protected Object laneColor(PlotLane myLane) {
			return null;
		}

		@Override
		protected void drawLine(Object color, int x1, int y1, int x2, int y2,
				int width) {
			// do nothing
		}

		@Override
		protected void drawCommitDot(int x, int y, int w, int h) {
			// do nothing
		}

		@Override
		protected void drawBoundaryDot(int x, int y, int w, int h) {
			// do nothing
		}

		@Override
		protected void drawText(String msg, int x, int y) {
			indentations.add(Integer.valueOf(x));
		}
	}

}
