/*
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com>
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.List;

import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.ListBranchCommand.ListMode;
import org.eclipse.jgit.api.errors.CannotDeleteCurrentBranchException;
import org.eclipse.jgit.api.errors.DetachedHeadException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NotMergedException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.junit.RepositoryTestCase;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.junit.Before;
import org.junit.Test;

public class BranchCommandTest extends RepositoryTestCase {
	private static final String SECOND_COMMIT_MESSAGE = "Second commit";

	private static final String INITIAL_COMMIT_MESSAGE = "Initial commit";

	private static final String TEST_TXT = "Test.txt"; //$NON-NLS-1$

	private static final String FOR_DELETE_BRANCH_NAME = "ForDelete"; //$NON-NLS-1$

	private static final String MASTER_BRANCH = "master"; //$NON-NLS-1$

	private static final String NEW_FORCE_BRANCH_NAME = "NewForce"; //$NON-NLS-1$

	private Git git;

	RevCommit initialCommit;

	RevCommit secondCommit;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		git = new Git(db);
		// checkout master
		git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
		// commit something
		writeTrashFile(TEST_TXT, "Hello world");
		git.add().addFilepattern(TEST_TXT).call();
		initialCommit = git.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
		writeTrashFile(TEST_TXT, "Some change");
		git.add().addFilepattern(TEST_TXT).call();
		secondCommit = git.commit().setMessage(SECOND_COMMIT_MESSAGE).call();
		// create a master branch
		RefUpdate rup = db.updateRef("refs/heads/master");
		rup.setNewObjectId(initialCommit.getId());
		rup.setForceUpdate(true);
		rup.update();
	}

	private Git setUpRepoWithRemote() throws Exception {
		Repository remoteRepository = createWorkRepository();
		try (Git remoteGit = new Git(remoteRepository)) {
			// commit something
			writeTrashFile(TEST_TXT, "Hello world");
			remoteGit.add().addFilepattern(TEST_TXT).call();
			initialCommit = remoteGit.commit().setMessage(INITIAL_COMMIT_MESSAGE).call();
			writeTrashFile(TEST_TXT, "Some change");
			remoteGit.add().addFilepattern(TEST_TXT).call();
			secondCommit = remoteGit.commit().setMessage(SECOND_COMMIT_MESSAGE).call();
			// create a master branch
			RefUpdate rup = remoteRepository.updateRef("refs/heads/master");
			rup.setNewObjectId(initialCommit.getId());
			rup.forceUpdate();

			Repository localRepository = createWorkRepository();
			Git localGit = new Git(localRepository);
			StoredConfig config = localRepository.getConfig();
			RemoteConfig rc = new RemoteConfig(config, "origin");
			rc.addURI(new URIish(remoteRepository.getDirectory().getAbsolutePath()));
			rc.addFetchRefSpec(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));
			rc.update(config);
			config.save();
			FetchResult res = localGit.fetch().setRemote("origin").call();
			assertFalse(res.getTrackingRefUpdates().isEmpty());
			rup = localRepository.updateRef("refs/heads/master");
			rup.setNewObjectId(initialCommit.getId());
			rup.forceUpdate();
			rup = localRepository.updateRef(Constants.HEAD);
			rup.link("refs/heads/master");
			rup.setNewObjectId(initialCommit.getId());
			rup.update();
			return localGit;
		}
	}

	@Test
	public void testCreateAndList() throws Exception {
		int localBefore;
		int remoteBefore;
		int allBefore;

		// invalid name not allowed
		try {
			git.branchCreate().setName("In va lid").call();
			fail("Create branch with invalid ref name should fail");
		} catch (InvalidRefNameException e) {
			// expected
		}
		// existing name not allowed w/o force
		try {
			git.branchCreate().setName(MASTER_BRANCH).call();
			fail("Create branch with existing ref name should fail");
		} catch (RefAlreadyExistsException e) {
			// expected
		}

		localBefore = git.branchList().call().size();
		remoteBefore = git.branchList().setListMode(ListMode.REMOTE).call()
				.size();
		allBefore = git.branchList().setListMode(ListMode.ALL).call().size();

		assertEquals(localBefore + remoteBefore, allBefore);
		Ref newBranch = createBranch(git, "NewForTestList", false,
				MASTER_BRANCH,
				null);
		assertEquals("refs/heads/NewForTestList", newBranch.getName());

		assertEquals(1, git.branchList().call().size() - localBefore);
		assertEquals(0, git.branchList().setListMode(ListMode.REMOTE).call()
				.size()
				- remoteBefore);
		assertEquals(1, git.branchList().setListMode(ListMode.ALL).call()
				.size()
				- allBefore);
		// we can only create local branches
		newBranch = createBranch(git,
				"refs/remotes/origin/NewRemoteForTestList", false, MASTER_BRANCH,
				null);
		assertEquals("refs/heads/refs/remotes/origin/NewRemoteForTestList",
				newBranch.getName());
		assertEquals(2, git.branchList().call().size() - localBefore);
		assertEquals(0, git.branchList().setListMode(ListMode.REMOTE).call()
				.size()
				- remoteBefore);
		assertEquals(2, git.branchList().setListMode(ListMode.ALL).call()
				.size()
				- allBefore);
	}

	@Test
	public void testListAllBranchesShouldNotDie() throws Exception {
		setUpRepoWithRemote().branchList().setListMode(ListMode.ALL).call();
	}

	@Test
	public void testListBranchesWithContains() throws Exception {
		git.branchCreate().setName("foo").setStartPoint(secondCommit).call();

		List<Ref> refs = git.branchList().call();
		assertEquals(2, refs.size());

		List<Ref> refsContainingSecond = git.branchList()
				.setContains(secondCommit.name()).call();
		assertEquals(1, refsContainingSecond.size());
		// master is on initial commit, so it should not be returned
		assertEquals("refs/heads/foo", refsContainingSecond.get(0).getName());
	}

	@Test
	public void testCreateFromCommit() throws Exception {
		Ref branch = git.branchCreate().setName("FromInitial").setStartPoint(
				initialCommit).call();
		assertEquals(initialCommit.getId(), branch.getObjectId());
		branch = git.branchCreate().setName("FromInitial2").setStartPoint(
				initialCommit.getId().name()).call();
		assertEquals(initialCommit.getId(), branch.getObjectId());
		try {
			git.branchCreate().setName("FromInitial").setStartPoint(
					secondCommit).call();
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		branch = git.branchCreate().setName("FromInitial").setStartPoint(
				secondCommit).setForce(true).call();
		assertEquals(secondCommit.getId(), branch.getObjectId());
	}

	@Test
	public void testCreateForce() throws Exception {
		// using commits
		Ref newBranch = createBranch(git, NEW_FORCE_BRANCH_NAME, false,
				secondCommit
				.getId().name(), null);
		assertEquals(newBranch.getTarget().getObjectId(), secondCommit.getId());
		try {
			newBranch = createBranch(git, NEW_FORCE_BRANCH_NAME, false,
					initialCommit
					.getId().name(), null);
			fail("Should have failed");
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		newBranch = createBranch(git, NEW_FORCE_BRANCH_NAME, true,
				initialCommit.getId()
				.name(), null);
		assertEquals(newBranch.getTarget().getObjectId(), initialCommit.getId());
		git.branchDelete().setBranchNames(NEW_FORCE_BRANCH_NAME).call();
		// using names

		git.branchCreate().setName(NEW_FORCE_BRANCH_NAME)
				.setStartPoint(MASTER_BRANCH).call();
		assertEquals(newBranch.getTarget().getObjectId(), initialCommit.getId());
		try {
			git.branchCreate().setName(NEW_FORCE_BRANCH_NAME)
					.setStartPoint(MASTER_BRANCH)
					.call();
			fail("Should have failed");
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		git.branchCreate().setName(NEW_FORCE_BRANCH_NAME)
				.setStartPoint(MASTER_BRANCH)
				.setForce(true).call();
		assertEquals(newBranch.getTarget().getObjectId(), initialCommit.getId());
	}

	@Test
	public void testCreateFromLightweightTag() throws Exception {
		RefUpdate rup = db.updateRef("refs/tags/V10");
		rup.setNewObjectId(initialCommit);
		rup.setExpectedOldObjectId(ObjectId.zeroId());
		rup.update();

		Ref branch = git.branchCreate().setName("FromLightweightTag")
				.setStartPoint("refs/tags/V10").call();
		assertEquals(initialCommit.getId(), branch.getObjectId());

	}

	@Test
	public void testCreateFromAnnotatetdTag() throws Exception {
		Ref tagRef = git.tag().setName("V10").setObjectId(secondCommit).call();
		Ref branch = git.branchCreate().setName("FromAnnotatedTag")
				.setStartPoint("refs/tags/V10").call();
		assertFalse(tagRef.getObjectId().equals(branch.getObjectId()));
		assertEquals(secondCommit.getId(), branch.getObjectId());
	}

	@Test
	public void testDelete() throws Exception {
		createBranch(git, FOR_DELETE_BRANCH_NAME, false, MASTER_BRANCH, null);
		git.branchDelete().setBranchNames(FOR_DELETE_BRANCH_NAME).call();
		// now point the branch to a non-merged commit
		createBranch(git, FOR_DELETE_BRANCH_NAME, false,
				secondCommit.getId().name(), null);
		try {
			git.branchDelete().setBranchNames(FOR_DELETE_BRANCH_NAME).call();
			fail("Deletion of a non-merged branch without force should have failed");
		} catch (NotMergedException e) {
			// expected
		}
		List<String> deleted = git.branchDelete().setBranchNames(FOR_DELETE_BRANCH_NAME)
				.setForce(true).call();
		assertEquals(1, deleted.size());
		assertEquals(Constants.R_HEADS + FOR_DELETE_BRANCH_NAME, deleted.get(0));
		createBranch(git, FOR_DELETE_BRANCH_NAME, false, MASTER_BRANCH, null);
		try {
			createBranch(git, FOR_DELETE_BRANCH_NAME, false, MASTER_BRANCH,
					null);
			fail("Repeated creation of same branch without force should fail");
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		// change starting point
		Ref newBranch = createBranch(git, FOR_DELETE_BRANCH_NAME, true,
				initialCommit
				.name(), null);
		assertEquals(newBranch.getTarget().getObjectId(), initialCommit.getId());
		newBranch = createBranch(git, FOR_DELETE_BRANCH_NAME, true,
				secondCommit.name(),
				null);
		assertEquals(newBranch.getTarget().getObjectId(), secondCommit.getId());
		git.branchDelete().setBranchNames(FOR_DELETE_BRANCH_NAME).setForce(true);
		try {
			git.branchDelete().setBranchNames(MASTER_BRANCH).call();
			fail("Deletion of checked out branch without force should have failed");
		} catch (CannotDeleteCurrentBranchException e) {
			// expected
		}
		try {
			git.branchDelete().setBranchNames(MASTER_BRANCH).setForce(true).call();
			fail("Deletion of checked out branch with force should have failed");
		} catch (CannotDeleteCurrentBranchException e) {
			// expected
		}
	}

	@Test
	public void testPullConfigRemoteBranch() throws Exception {
		Git localGit = setUpRepoWithRemote();
		Ref remote = localGit.branchList().setListMode(ListMode.REMOTE).call()
				.get(0);
		assertEquals("refs/remotes/origin/master", remote.getName());
		// by default, we should create pull configuration
		createBranch(localGit, "newFromRemote", false, remote.getName(), null);
		assertEquals("origin", localGit.getRepository().getConfig().getString(
				"branch", "newFromRemote", "remote"));
		localGit.branchDelete().setBranchNames("newFromRemote").call();
		// the pull configuration should be gone after deletion
		assertNull(localGit.getRepository().getConfig().getString("branch",
				"newFromRemote", "remote"));

		createBranch(localGit, "newFromRemote", false, remote.getName(), null);
		assertEquals("origin", localGit.getRepository().getConfig().getString(
				"branch", "newFromRemote", "remote"));
		localGit.branchDelete().setBranchNames("refs/heads/newFromRemote")
				.call();
		// the pull configuration should be gone after deletion
		assertNull(localGit.getRepository().getConfig().getString("branch",
				"newFromRemote", "remote"));

		// use --no-track
		createBranch(localGit, "newFromRemote", false, remote.getName(),
				SetupUpstreamMode.NOTRACK);
		assertNull(localGit.getRepository().getConfig().getString("branch",
				"newFromRemote", "remote"));
		localGit.branchDelete().setBranchNames("newFromRemote").call();
	}

	@Test
	public void testPullConfigLocalBranch() throws Exception {
		Git localGit = setUpRepoWithRemote();
		// by default, we should not create pull configuration
		createBranch(localGit, "newFromMaster", false, MASTER_BRANCH, null);
		assertNull(localGit.getRepository().getConfig().getString("branch",
				"newFromMaster", "remote"));
		localGit.branchDelete().setBranchNames("newFromMaster").call();
		// use --track
		createBranch(localGit, "newFromMaster", false, MASTER_BRANCH,
				SetupUpstreamMode.TRACK);
		assertEquals(".", localGit.getRepository().getConfig().getString(
				"branch", "newFromMaster", "remote"));
		localGit.branchDelete().setBranchNames("refs/heads/newFromMaster")
				.call();
		// the pull configuration should be gone after deletion
		assertNull(localGit.getRepository().getConfig().getString("branch",
				"newFromRemote", "remote"));
	}

	@Test
	public void testPullConfigRenameLocalBranch() throws Exception {
		Git localGit = setUpRepoWithRemote();
		// by default, we should not create pull configuration
		createBranch(localGit, "newFromMaster", false, MASTER_BRANCH, null);
		assertNull(localGit.getRepository().getConfig().getString("branch",
				"newFromMaster", "remote"));
		localGit.branchDelete().setBranchNames("newFromMaster").call();
		// use --track
		createBranch(localGit, "newFromMaster", false, MASTER_BRANCH,
				SetupUpstreamMode.TRACK);
		assertEquals(".", localGit.getRepository().getConfig().getString(
				"branch", "newFromMaster", "remote"));
		localGit.branchRename().setOldName("newFromMaster").setNewName(
				"renamed").call();
		assertNull(".", localGit.getRepository().getConfig().getString(
				"branch", "newFromMaster", "remote"));
		assertEquals(".", localGit.getRepository().getConfig().getString(
				"branch", "renamed", "remote"));
		localGit.branchDelete().setBranchNames("renamed").call();
		// the pull configuration should be gone after deletion
		assertNull(localGit.getRepository().getConfig().getString("branch",
				"newFromRemote", "remote"));
	}

	@Test
	public void testRenameLocalBranch() throws Exception {
		// null newName not allowed
		try {
			git.branchRename().call();
		} catch (InvalidRefNameException e) {
			// expected
		}
		// invalid newName not allowed
		try {
			git.branchRename().setNewName("In va lid").call();
		} catch (InvalidRefNameException e) {
			// expected
		}
		// not existing name not allowed
		try {
			git.branchRename().setOldName("notexistingbranch").setNewName(
					"newname").call();
		} catch (RefNotFoundException e) {
			// expected
		}
		// create some branch
		createBranch(git, "existing", false, MASTER_BRANCH, null);
		// a local branch
		Ref branch = createBranch(git, "fromMasterForRename", false,
				MASTER_BRANCH,
				null);
		assertEquals(Constants.R_HEADS + "fromMasterForRename", branch
				.getName());
		Ref renamed = git.branchRename().setOldName("fromMasterForRename")
				.setNewName("newName").call();
		assertEquals(Constants.R_HEADS + "newName", renamed.getName());
		try {
			git.branchRename().setOldName(renamed.getName()).setNewName(
					"existing").call();
			fail("Should have failed");
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		try {
			git.branchRename().setNewName("In va lid").call();
			fail("Rename with invalid ref name should fail");
		} catch (InvalidRefNameException e) {
			// expected
		}
		// rename without old name and detached head not allowed
		RefUpdate rup = git.getRepository().updateRef(Constants.HEAD, true);
		rup.setNewObjectId(initialCommit);
		rup.forceUpdate();
		try {
			git.branchRename().setNewName("detached").call();
		} catch (DetachedHeadException e) {
			// expected
		}
	}

	@Test
	public void testRenameRemoteTrackingBranch() throws Exception {
		Git localGit = setUpRepoWithRemote();
		Ref remoteBranch = localGit.branchList().setListMode(ListMode.REMOTE)
				.call().get(0);
		Ref renamed = localGit.branchRename()
				.setOldName(remoteBranch.getName()).setNewName("newRemote")
				.call();
		assertEquals(Constants.R_REMOTES + "newRemote", renamed.getName());
	}

	@Test
	public void testCreationImplicitStart() throws Exception {
		git.branchCreate().setName("topic").call();
		assertEquals(db.resolve("HEAD"), db.resolve("topic"));
	}

	@Test
	public void testCreationNullStartPoint() throws Exception {
		String startPoint = null;
		git.branchCreate().setName("topic").setStartPoint(startPoint).call();
		assertEquals(db.resolve("HEAD"), db.resolve("topic"));
	}

	public Ref createBranch(Git actGit, String name, boolean force,
			String startPoint, SetupUpstreamMode mode)
			throws JGitInternalException, GitAPIException {
		CreateBranchCommand cmd = actGit.branchCreate();
		cmd.setName(name);
		cmd.setForce(force);
		cmd.setStartPoint(startPoint);
		cmd.setUpstreamMode(mode);
		return cmd.call();
	}
}
