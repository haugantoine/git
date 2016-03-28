/*
 * Copyright (C) 2010, 2013 Chris Aniszczyk <caniszczyk@gmail.com>
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
package org.eclipse.jgit.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.List;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidTagNameException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Test;

public class TagCommandTest extends RepositoryTestCase {

	private static final String REFS_TAGS = "refs/tags/"; //$NON-NLS-1$

	private static final String TAG_V10 = "v10"; //$NON-NLS-1$

	private static final String TAG_V2 = "v2"; //$NON-NLS-1$

	private static final String TAG_V3 = "v3"; //$NON-NLS-1$

	private static final String FILEPATTERN = "*"; //$NON-NLS-1$

	private static final String WE_SHOULD_HAVE_FAILED_DUE_TO_A_BAD_TAG_NAME = "We should have failed due to a bad tag name"; //$NON-NLS-1$

	private static final String BAD_TAG_NAME = "bad~tag~name"; //$NON-NLS-1$

	private static final String WE_SHOULD_HAVE_FAILED_WITHOUT_A_TAG_NAME = "We should have failed without a tag name"; //$NON-NLS-1$

	private static final String SOME_MESSAGE = "some message"; //$NON-NLS-1$

	private static final String THIRD_COMMIT_MESSAGE = "third commit"; //$NON-NLS-1$

	private static final String SECOND_COMMIT_MESSAGE = "second commit"; //$NON-NLS-1$

	private static final String TAG_NAME = "tag"; //$NON-NLS-1$

	private static final String INITIAL_COMMIT_MESSAGE = "initial commit"; //$NON-NLS-1$

	@Test
	public void testTaggingOnHead() throws GitAPIException, IOException {
		try (Git git = new Git(db);
				RevWalk walk = new RevWalk(db)) {
			RevCommit commit = git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			Ref tagRef = git.tag().setName(TAG_NAME).call();
			assertEquals(commit.getId(), db.peel(tagRef).getPeeledObjectId());
			assertEquals(TAG_NAME, walk.parseTag(tagRef.getObjectId()).getTagName());
		}
	}

	@Test
	public void testTagging() throws GitAPIException, JGitInternalException {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			RevCommit commit = git.commit().setMessage(SECOND_COMMIT_MESSAGE).call();
			git.commit().setMessage(THIRD_COMMIT_MESSAGE).call();
			Ref tagRef = git.tag().setObjectId(commit).setName(TAG_NAME).call();
			assertEquals(commit.getId(), db.peel(tagRef).getPeeledObjectId());
		}
	}

	@Test
	public void testUnannotatedTagging() throws GitAPIException,
			JGitInternalException {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			RevCommit commit = git.commit().setMessage(SECOND_COMMIT_MESSAGE).call();
			git.commit().setMessage(THIRD_COMMIT_MESSAGE).call();
			Ref tagRef = git.tag().setObjectId(commit).setName(TAG_NAME)
					.setAnnotated(false).call();
			assertEquals(commit.getId(), tagRef.getObjectId());
		}
	}

	@Test
	public void testEmptyTagName() throws GitAPIException {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			try {
				// forget to tag name
				git.tag().setMessage(SOME_MESSAGE).call();
				fail(WE_SHOULD_HAVE_FAILED_WITHOUT_A_TAG_NAME);
			} catch (InvalidTagNameException e) {
				// should hit here
			}
		}
	}

	@Test
	public void testInvalidTagName() throws GitAPIException {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			try {
				git.tag().setName(BAD_TAG_NAME).setMessage(SOME_MESSAGE).call();
				fail(WE_SHOULD_HAVE_FAILED_DUE_TO_A_BAD_TAG_NAME);
			} catch (InvalidTagNameException e) {
				// should hit here
			}
		}
	}

	@Test
	public void testDelete() throws Exception {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			Ref tagRef = git.tag().setName(TAG_NAME).call();
			assertEquals(1, db.getTags().size());

			List<String> deleted = git.tagDelete().setTags(tagRef.getName())
					.call();
			assertEquals(1, deleted.size());
			assertEquals(tagRef.getName(), deleted.get(0));
			assertEquals(0, db.getTags().size());

			Ref tagRef1 = git.tag().setName(TAG_NAME + 1).call();
			Ref tagRef2 = git.tag().setName(TAG_NAME + 2).call();
			assertEquals(2, db.getTags().size());
			deleted = git.tagDelete().setTags(tagRef1.getName(), tagRef2.getName())
					.call();
			assertEquals(2, deleted.size());
			assertEquals(0, db.getTags().size());
		}
	}

	@Test
	public void testDeleteFullName() throws Exception {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			Ref tagRef = git.tag().setName(TAG_NAME).call();
			assertEquals(1, db.getTags().size());

			List<String> deleted = git.tagDelete()
					.setTags(Repository.shortenRefName(tagRef.getName())).call();
			assertEquals(1, deleted.size());
			assertEquals(tagRef.getName(), deleted.get(0));
			assertEquals(0, db.getTags().size());
		}
	}

	@Test
	public void testDeleteEmptyTagNames() throws Exception {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();

			List<String> deleted = git.tagDelete().setTags().call();
			assertEquals(0, deleted.size());
		}
	}

	@Test
	public void testDeleteNonExisting() throws Exception {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();

			List<String> deleted = git.tagDelete().setTags(TAG_NAME).call();
			assertEquals(0, deleted.size());
		}
	}

	@Test
	public void testDeleteBadName() throws Exception {
		try (Git git = new Git(db)) {
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();

			List<String> deleted = git.tagDelete().setTags(BAD_TAG_NAME)
					.call();
			assertEquals(0, deleted.size());
		}
	}

	@Test
	public void testShouldNotBlowUpIfThereAreNoTagsInRepository()
			throws Exception {
		try (Git git = new Git(db)) {
			git.add().addFilepattern(FILEPATTERN).call();
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			List<Ref> list = git.tagList().call();
			assertEquals(0, list.size());
		}
	}

	@Test
	public void testShouldNotBlowUpIfThereAreNoCommitsInRepository()
			throws Exception {
		try (Git git = new Git(db)) {
			List<Ref> list = git.tagList().call();
			assertEquals(0, list.size());
		}
	}

	@Test
	public void testListAllTagsInRepositoryInOrder() throws Exception {
		try (Git git = new Git(db)) {
			git.add().addFilepattern(FILEPATTERN).call();
			git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();

			git.tag().setName(TAG_V3).call();
			git.tag().setName(TAG_V2).call();
			git.tag().setName(TAG_V10).call();

			List<Ref> list = git.tagList().call();

			assertEquals(3, list.size());
			assertEquals(REFS_TAGS + TAG_V10, list.get(0).getName());
			assertEquals(REFS_TAGS + TAG_V2, list.get(1).getName());
			assertEquals(REFS_TAGS + TAG_V3, list.get(2).getName());
		}
	}

}
