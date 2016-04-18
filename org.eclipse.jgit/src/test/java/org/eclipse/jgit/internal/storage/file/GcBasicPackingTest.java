/*
 * Copyright (C) 2012, Christian Halstrick <christian.halstrick@sap.com>
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

package org.eclipse.jgit.internal.storage.file;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;

import org.eclipse.jgit.junit.TestRepository.BranchBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class GcBasicPackingTest extends GcTestCase {
	@DataPoints
	public static boolean[] aggressiveValues = { true, false };

	@Theory
	public void repackEmptyRepo_noPackCreated(boolean aggressive)
			throws IOException {
		configureGc(gc, aggressive);
		gc.repack();
		assertEquals(0, repo.getObjectDatabase().getPacks().size());
	}

	@Theory
	public void testPackRepoWithNoRefs(boolean aggressive) throws Exception {
		tr.commit().add("A", "A").add("B", "B").create();
		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		configureGc(gc, aggressive);
		gc.gc();
		stats = gc.getStatistics();
		assertEquals(4, stats.numberOfLooseObjects);
		assertEquals(0, stats.numberOfPackedObjects);
		assertEquals(0, stats.numberOfPackFiles);
		assertEquals(0, stats.numberOfBitmaps);
	}
	private void configureGc(GC myGc, boolean aggressive) {
		PackConfig pconfig = new PackConfig(repo);
		if (aggressive) {
			pconfig.setDeltaSearchWindowSize(250);
			pconfig.setMaxDeltaDepth(250);
			pconfig.setReuseObjects(false);
		} else
			pconfig = new PackConfig(repo);
		myGc.setPackConfig(pconfig);
	}
}
