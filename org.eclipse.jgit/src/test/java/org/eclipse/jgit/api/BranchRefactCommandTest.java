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

public class BranchRefactCommandTest extends RepositoryTestCase {
	private static final String SOME_CHANGE = "Some change"; //$NON-NLS-1$

	private static final String FIRST_CHANGE = "Hello world"; //$NON-NLS-1$

	private static final String FROM_MASTER_FOR_RENAME = "fromMasterForRename"; //$NON-NLS-1$

	private static final String NEW_NAME_BRANCH = "newName"; //$NON-NLS-1$

	private static final String NEWNAME_BRANCH = "newname"; //$NON-NLS-1$

	private static final String IN_VA_LID_BRANCH_NAME = "In va lid"; //$NON-NLS-1$

	private static final String ALL_STRING = "."; //$NON-NLS-1$

	private static final String RENAMED_BRANCH_NAME = "renamed"; //$NON-NLS-1$

	private static final String NEW_FROM_REMOTE = "newFromRemote"; //$NON-NLS-1$

	private static final String FROM_INITIAL_BRANCH_NAME = "FromInitial"; //$NON-NLS-1$

	private static final String REFS_HEADS_MASTER = "refs/heads/master"; //$NON-NLS-1$

	private static final String ORIGIN_REMOTE_NAME = "origin"; //$NON-NLS-1$

	private static final String REMOTE_NAME = "remote"; //$NON-NLS-1$

	private static final String BRANCH_NAME = "branch"; //$NON-NLS-1$

	private static final String NEW_FROM_MASTER = "newFromMaster"; //$NON-NLS-1$

	private static final String SECOND_COMMIT_MESSAGE = "Second commit"; //$NON-NLS-1$

	private static final String INITIAL_COMMIT_MESSAGE = "Initial commit"; //$NON-NLS-1$

	private static final String TEST_TXT = "Test.txt"; //$NON-NLS-1$

	private static final String FOR_DELETE_BRANCH_NAME = "ForDelete"; //$NON-NLS-1$

	private static final String MASTER_BRANCH = "master"; //$NON-NLS-1$

	private static final String NEW_FORCE_BRANCH_NAME = "NewForce"; //$NON-NLS-1$

	RevCommit initialCommit;

	RevCommit secondCommit;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		// checkout master
		new CommitCommand(db).setMessage(INITIAL_COMMIT_MESSAGE).call();
		// commit something
		writeTrashFile(TEST_TXT, FIRST_CHANGE);
		new AddCommand(db).addFilepattern(TEST_TXT).call();
		initialCommit = new CommitCommand(db).setMessage(INITIAL_COMMIT_MESSAGE)
				.call();
		writeTrashFile(TEST_TXT, SOME_CHANGE);
		new AddCommand(db).addFilepattern(TEST_TXT).call();
		secondCommit = new CommitCommand(db).setMessage(SECOND_COMMIT_MESSAGE)
				.call();
		// create a master branch
		RefUpdate rup = db.updateRef(REFS_HEADS_MASTER);
		rup.setNewObjectId(initialCommit.getId());
		rup.setForceUpdate(true);
		rup.update();
	}

	private Repository setUpRepoWithRemote() throws Exception {
		Repository remoteRepository = createWorkRepository();
		// commit something
		writeTrashFile(TEST_TXT, FIRST_CHANGE);
		new AddCommand(remoteRepository).addFilepattern(TEST_TXT).call();
		initialCommit = new CommitCommand(remoteRepository)
				.setMessage(INITIAL_COMMIT_MESSAGE).call();
		writeTrashFile(TEST_TXT, SOME_CHANGE);
		new AddCommand(remoteRepository).addFilepattern(TEST_TXT).call();
		secondCommit = new CommitCommand(remoteRepository)
				.setMessage(SECOND_COMMIT_MESSAGE).call();
		// create a master branch
		RefUpdate rup = remoteRepository.updateRef(REFS_HEADS_MASTER);
		rup.setNewObjectId(initialCommit.getId());
		rup.forceUpdate();

		Repository localRepository = createWorkRepository();
		StoredConfig config = localRepository.getConfig();
		RemoteConfig rc = new RemoteConfig(config, ORIGIN_REMOTE_NAME);
		rc.addURI(
				new URIish(remoteRepository.getDirectory().getAbsolutePath()));
		rc.addFetchRefSpec(new RefSpec("+refs/heads/*:refs/remotes/origin/*"));
		rc.update(config);
		config.save();
		FetchResult res = new FetchCommand(localRepository)
				.setRemote(ORIGIN_REMOTE_NAME).call();
		assertFalse(res.getTrackingRefUpdates().isEmpty());
		rup = localRepository.updateRef(REFS_HEADS_MASTER);
		rup.setNewObjectId(initialCommit.getId());
		rup.forceUpdate();
		rup = localRepository.updateRef(Constants.HEAD);
		rup.link(REFS_HEADS_MASTER);
		rup.setNewObjectId(initialCommit.getId());
		rup.update();
		return localRepository;
	}

	@Test
	public void testCreateAndList() throws Exception {
		int localBefore;
		int remoteBefore;
		int allBefore;

		// invalid name not allowed
		try {
			new CreateBranchCommand(db).setName(IN_VA_LID_BRANCH_NAME).call();
			fail("Create branch with invalid ref name should fail");
		} catch (InvalidRefNameException e) {
			// expected
		}
		// existing name not allowed w/o force
		try {
			new CreateBranchCommand(db).setName(MASTER_BRANCH).call();
			fail("Create branch with existing ref name should fail");
		} catch (RefAlreadyExistsException e) {
			// expected
		}

		localBefore = new ListBranchCommand(db).call().size();
		remoteBefore = new ListBranchCommand(db).setListMode(ListMode.REMOTE)
				.call().size();
		allBefore = new ListBranchCommand(db).setListMode(ListMode.ALL).call()
				.size();

		assertEquals(localBefore + remoteBefore, allBefore);
		Ref newBranch = createBranch("NewForTestList", false, MASTER_BRANCH,
				null);
		assertEquals("refs/heads/NewForTestList", newBranch.getName());

		assertEquals(1, new ListBranchCommand(db).call().size() - localBefore);
		assertEquals(0, new ListBranchCommand(db).setListMode(ListMode.REMOTE)
				.call().size() - remoteBefore);
		assertEquals(1, new ListBranchCommand(db).setListMode(ListMode.ALL)
				.call().size() - allBefore);
		// we can only create local branches
		newBranch = createBranch("refs/remotes/origin/NewRemoteForTestList",
				false, MASTER_BRANCH, null);
		assertEquals("refs/heads/refs/remotes/origin/NewRemoteForTestList",
				newBranch.getName());
		assertEquals(2, new ListBranchCommand(db).call().size() - localBefore);
		assertEquals(0, new ListBranchCommand(db).setListMode(ListMode.REMOTE)
				.call().size() - remoteBefore);
		assertEquals(2, new ListBranchCommand(db).setListMode(ListMode.ALL)
				.call().size() - allBefore);
	}

	@Test
	public void testListAllBranchesShouldNotDie() throws Exception {
		new ListBranchCommand(setUpRepoWithRemote()).setListMode(ListMode.ALL)
				.call();
	}

	@Test
	public void testListBranchesWithContains() throws Exception {
		new CreateBranchCommand(db).setName("foo").setStartPoint(secondCommit)
				.call();

		List<Ref> refs = new ListBranchCommand(db).call();
		assertEquals(2, refs.size());

		List<Ref> refsContainingSecond = new ListBranchCommand(db)
				.setContains(secondCommit.name()).call();
		assertEquals(1, refsContainingSecond.size());
		// master is on initial commit, so it should not be returned
		assertEquals("refs/heads/foo", refsContainingSecond.get(0).getName());
	}

	@Test
	public void testCreateFromCommit() throws Exception {
		Ref branch = new CreateBranchCommand(db)
				.setName(FROM_INITIAL_BRANCH_NAME).setStartPoint(initialCommit)
				.call();
		assertEquals(initialCommit.getId(), branch.getObjectId());
		branch = new CreateBranchCommand(db)
				.setName(FROM_INITIAL_BRANCH_NAME + 2)
				.setStartPoint(initialCommit.getId().name()).call();
		assertEquals(initialCommit.getId(), branch.getObjectId());
		try {
			new CreateBranchCommand(db).setName(FROM_INITIAL_BRANCH_NAME)
					.setStartPoint(secondCommit).call();
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		branch = new CreateBranchCommand(db).setName(FROM_INITIAL_BRANCH_NAME)
				.setStartPoint(secondCommit).setForce(true).call();
		assertEquals(secondCommit.getId(), branch.getObjectId());
	}

	@Test
	public void testCreateForce() throws Exception {
		// using commits
		Ref newBranch = createBranch(NEW_FORCE_BRANCH_NAME, false,
				secondCommit.getId().name(), null);
		assertEquals(newBranch.getTarget().getObjectId(), secondCommit.getId());
		try {
			newBranch = createBranch(NEW_FORCE_BRANCH_NAME, false,
					initialCommit.getId().name(), null);
			fail("Should have failed");
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		newBranch = createBranch(NEW_FORCE_BRANCH_NAME, true,
				initialCommit.getId().name(), null);
		assertEquals(newBranch.getTarget().getObjectId(),
				initialCommit.getId());
		new DeleteBranchCommand(db).setBranchNames(NEW_FORCE_BRANCH_NAME)
				.call();
		// using names

		new CreateBranchCommand(db).setName(NEW_FORCE_BRANCH_NAME)
				.setStartPoint(MASTER_BRANCH).call();
		assertEquals(newBranch.getTarget().getObjectId(),
				initialCommit.getId());
		try {
			new CreateBranchCommand(db).setName(NEW_FORCE_BRANCH_NAME)
					.setStartPoint(MASTER_BRANCH).call();
			fail("Should have failed");
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		new CreateBranchCommand(db).setName(NEW_FORCE_BRANCH_NAME)
				.setStartPoint(MASTER_BRANCH).setForce(true).call();
		assertEquals(newBranch.getTarget().getObjectId(),
				initialCommit.getId());
	}

	@Test
	public void testCreateFromLightweightTag() throws Exception {
		RefUpdate rup = db.updateRef("refs/tags/V10");
		rup.setNewObjectId(initialCommit);
		rup.setExpectedOldObjectId(ObjectId.zeroId());
		rup.update();

		Ref branch = new CreateBranchCommand(db).setName("FromLightweightTag")
				.setStartPoint("refs/tags/V10").call();
		assertEquals(initialCommit.getId(), branch.getObjectId());

	}

	@Test
	public void testCreateFromAnnotatetdTag() throws Exception {
		Ref tagRef = new TagCommand(db).setName("V10").setObjectId(secondCommit)
				.call();
		Ref branch = new CreateBranchCommand(db).setName("FromAnnotatedTag")
				.setStartPoint("refs/tags/V10").call();
		assertFalse(tagRef.getObjectId().equals(branch.getObjectId()));
		assertEquals(secondCommit.getId(), branch.getObjectId());
	}

	@Test
	public void testDelete() throws Exception {
		createBranch(FOR_DELETE_BRANCH_NAME, false, MASTER_BRANCH, null);
		new DeleteBranchCommand(db).setBranchNames(FOR_DELETE_BRANCH_NAME)
				.call();
		// now point the branch to a non-merged commit
		createBranch(FOR_DELETE_BRANCH_NAME, false, secondCommit.getId().name(),
				null);
		try {
			new DeleteBranchCommand(db).setBranchNames(FOR_DELETE_BRANCH_NAME)
					.call();
			fail("Deletion of a non-merged branch without force should have failed");
		} catch (NotMergedException e) {
			// expected
		}
		List<String> deleted = new DeleteBranchCommand(db)
				.setBranchNames(FOR_DELETE_BRANCH_NAME).setForce(true).call();
		assertEquals(1, deleted.size());
		assertEquals(Constants.R_HEADS + FOR_DELETE_BRANCH_NAME,
				deleted.get(0));
		createBranch(FOR_DELETE_BRANCH_NAME, false, MASTER_BRANCH, null);
		try {
			createBranch(FOR_DELETE_BRANCH_NAME, false, MASTER_BRANCH, null);
			fail("Repeated creation of same branch without force should fail");
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		// change starting point
		Ref newBranch = createBranch(FOR_DELETE_BRANCH_NAME, true,
				initialCommit.name(), null);
		assertEquals(newBranch.getTarget().getObjectId(),
				initialCommit.getId());
		newBranch = createBranch(FOR_DELETE_BRANCH_NAME, true,
				secondCommit.name(), null);
		assertEquals(newBranch.getTarget().getObjectId(), secondCommit.getId());
		new DeleteBranchCommand(db).setBranchNames(FOR_DELETE_BRANCH_NAME)
				.setForce(true);
		try {
			new DeleteBranchCommand(db).setBranchNames(MASTER_BRANCH).call();
			fail("Deletion of checked out branch without force should have failed");
		} catch (CannotDeleteCurrentBranchException e) {
			// expected
		}
		try {
			new DeleteBranchCommand(db).setBranchNames(MASTER_BRANCH)
					.setForce(true).call();
			fail("Deletion of checked out branch with force should have failed");
		} catch (CannotDeleteCurrentBranchException e) {
			// expected
		}
	}

	@Test
	public void testPullConfigRemoteBranch() throws Exception {
		Repository localRepository = setUpRepoWithRemote();
		Ref remote = new ListBranchCommand(localRepository)
				.setListMode(ListMode.REMOTE).call().get(0);
		assertEquals("refs/remotes/origin/master", remote.getName());
		// by default, we should create pull configuration
		createBranchLocal(localRepository, NEW_FROM_REMOTE, false,
				remote.getName(), null);
		assertEquals(ORIGIN_REMOTE_NAME, localRepository.getConfig()
				.getString(BRANCH_NAME, NEW_FROM_REMOTE, REMOTE_NAME));
		new DeleteBranchCommand(localRepository).setBranchNames(NEW_FROM_REMOTE)
				.call();
		// the pull configuration should be gone after deletion
		assertNull(localRepository.getConfig().getString(BRANCH_NAME,
				NEW_FROM_REMOTE, REMOTE_NAME));

		createBranchLocal(localRepository, NEW_FROM_REMOTE, false,
				remote.getName(), null);
		assertEquals(ORIGIN_REMOTE_NAME, localRepository.getConfig()
				.getString(BRANCH_NAME, NEW_FROM_REMOTE, REMOTE_NAME));
		new DeleteBranchCommand(localRepository)
				.setBranchNames("refs/heads/newFromRemote").call();
		// the pull configuration should be gone after deletion
		assertNull(localRepository.getConfig().getString(BRANCH_NAME,
				NEW_FROM_REMOTE, REMOTE_NAME));

		// use --no-track
		createBranchLocal(localRepository, NEW_FROM_REMOTE, false,
				remote.getName(), SetupUpstreamMode.NOTRACK);
		assertNull(localRepository.getConfig().getString(BRANCH_NAME,
				NEW_FROM_REMOTE, REMOTE_NAME));
		new DeleteBranchCommand(localRepository).setBranchNames(NEW_FROM_REMOTE)
				.call();
	}

	@Test
	public void testPullConfigLocalBranch() throws Exception {
		Repository localRepository = setUpRepoWithRemote();
		// by default, we should not create pull configuration
		createBranchLocal(localRepository, NEW_FROM_MASTER, false,
				MASTER_BRANCH, null);
		assertNull(localRepository.getConfig().getString(BRANCH_NAME,
				NEW_FROM_MASTER, REMOTE_NAME));
		new DeleteBranchCommand(localRepository).setBranchNames(NEW_FROM_MASTER)
				.call();
		// use --track
		createBranchLocal(localRepository, NEW_FROM_MASTER, false,
				MASTER_BRANCH, SetupUpstreamMode.TRACK);
		assertEquals(ALL_STRING, localRepository.getConfig()
				.getString(BRANCH_NAME, NEW_FROM_MASTER, REMOTE_NAME));
		new DeleteBranchCommand(localRepository)
				.setBranchNames("refs/heads/newFromMaster").call();
		// the pull configuration should be gone after deletion
		assertNull(localRepository.getConfig().getString(BRANCH_NAME,
				NEW_FROM_REMOTE, REMOTE_NAME));
	}

	@Test
	public void testPullConfigRenameLocalBranch() throws Exception {
		Repository localRepository = setUpRepoWithRemote();
		// by default, we should not create pull configuration
		createBranchLocal(localRepository, NEW_FROM_MASTER, false,
				MASTER_BRANCH, null);
		assertNull(localRepository.getConfig().getString(BRANCH_NAME,
				NEW_FROM_MASTER, REMOTE_NAME));
		new DeleteBranchCommand(localRepository).setBranchNames(NEW_FROM_MASTER)
				.call();
		// use --track
		createBranchLocal(localRepository, NEW_FROM_MASTER, false,
				MASTER_BRANCH, SetupUpstreamMode.TRACK);
		assertEquals(ALL_STRING, localRepository.getConfig()
				.getString(BRANCH_NAME, NEW_FROM_MASTER, REMOTE_NAME));
		new RenameBranchCommand(localRepository).setOldName(NEW_FROM_MASTER)
				.setNewName(RENAMED_BRANCH_NAME).call();
		assertNull(ALL_STRING, localRepository.getConfig()
				.getString(BRANCH_NAME, NEW_FROM_MASTER, REMOTE_NAME));
		assertEquals(ALL_STRING, localRepository.getConfig()
				.getString(BRANCH_NAME, RENAMED_BRANCH_NAME, REMOTE_NAME));
		new DeleteBranchCommand(localRepository)
				.setBranchNames(RENAMED_BRANCH_NAME).call();
		// the pull configuration should be gone after deletion
		assertNull(localRepository.getConfig().getString(BRANCH_NAME,
				NEW_FROM_REMOTE, REMOTE_NAME));
	}

	@Test
	public void testRenameLocalBranch() throws Exception {
		// null newName not allowed
		try {
			new RenameBranchCommand(db).call();
		} catch (InvalidRefNameException e) {
			// expected
		}
		// invalid newName not allowed
		try {
			new RenameBranchCommand(db).setNewName(IN_VA_LID_BRANCH_NAME)
					.call();
		} catch (InvalidRefNameException e) {
			// expected
		}
		// not existing name not allowed
		try {
			new RenameBranchCommand(db).setOldName("notexistingbranch")
					.setNewName(NEWNAME_BRANCH).call();
		} catch (RefNotFoundException e) {
			// expected
		}
		// create some branch
		createBranch("existing", false, MASTER_BRANCH, null);
		// a local branch
		Ref branch = createBranch(FROM_MASTER_FOR_RENAME, false, MASTER_BRANCH,
				null);
		assertEquals(Constants.R_HEADS + FROM_MASTER_FOR_RENAME,
				branch.getName());
		Ref renamed = new RenameBranchCommand(db)
				.setOldName(FROM_MASTER_FOR_RENAME).setNewName(NEW_NAME_BRANCH)
				.call();
		assertEquals(Constants.R_HEADS + NEW_NAME_BRANCH, renamed.getName());
		try {
			new RenameBranchCommand(db).setOldName(renamed.getName())
					.setNewName("existing").call();
			fail("Should have failed");
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		try {
			new RenameBranchCommand(db).setNewName(IN_VA_LID_BRANCH_NAME)
					.call();
			fail("Rename with invalid ref name should fail");
		} catch (InvalidRefNameException e) {
			// expected
		}
		// rename without old name and detached head not allowed
		RefUpdate rup = db.updateRef(Constants.HEAD, true);
		rup.setNewObjectId(initialCommit);
		rup.forceUpdate();
		try {
			new RenameBranchCommand(db).setNewName("detached").call();
		} catch (DetachedHeadException e) {
			// expected
		}
	}

	@Test
	public void testRenameRemoteTrackingBranch() throws Exception {
		Repository localRepository = setUpRepoWithRemote();
		Ref remoteBranch = new ListBranchCommand(localRepository)
				.setListMode(ListMode.REMOTE).call().get(0);
		Ref renamed = new RenameBranchCommand(localRepository)
				.setOldName(remoteBranch.getName()).setNewName("newRemote")
				.call();
		assertEquals(Constants.R_REMOTES + "newRemote", renamed.getName());
	}

	@Test
	public void testCreationImplicitStart() throws Exception {
		new CreateBranchCommand(db).setName("topic").call();
		assertEquals(db.resolve("HEAD"), db.resolve("topic"));
	}

	@Test
	public void testCreationNullStartPoint() throws Exception {
		String startPoint = null;
		new CreateBranchCommand(db).setName("topic").setStartPoint(startPoint)
				.call();
		assertEquals(db.resolve("HEAD"), db.resolve("topic"));
	}

	public Ref createBranch(String name, boolean force, String startPoint,
			SetupUpstreamMode mode)
			throws JGitInternalException, GitAPIException {
		CreateBranchCommand cmd = new CreateBranchCommand(db);
		cmd.setName(name);
		cmd.setForce(force);
		cmd.setStartPoint(startPoint);
		cmd.setUpstreamMode(mode);
		return cmd.call();
	}

	public Ref createBranchLocal(Repository repo, String name, boolean force,
			String startPoint, SetupUpstreamMode mode)
			throws JGitInternalException, GitAPIException {
		CreateBranchCommand cmd = new CreateBranchCommand(repo);
		cmd.setName(name);
		cmd.setForce(force);
		cmd.setStartPoint(startPoint);
		cmd.setUpstreamMode(mode);
		return cmd.call();
	}

}

