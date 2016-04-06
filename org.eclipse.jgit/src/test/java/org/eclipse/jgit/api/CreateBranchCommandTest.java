package org.eclipse.jgit.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.when;

import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

@Ignore
@RunWith(MockitoJUnitRunner.class)
public class CreateBranchCommandTest {

	private static final String FROM_LIGHTWEIGHT_TAG = "FromLightweightTag"; //$NON-NLS-1$

	private static final String FROM_ANNOTATED_TAG = "FromAnnotatedTag"; //$NON-NLS-1$

	private static final String BRANCH_NAME = "V10"; //$NON-NLS-1$

	private static final String REFS_TAGS = "refs/tags/"; //$NON-NLS-1$

	private static final String HEAD = "HEAD"; //$NON-NLS-1$

	private static final String TOPIC_BRANCH_NAME = "topic"; //$NON-NLS-1$

	private static final String FROM_INITIAL_BRANCH_NAME = "FromInitial"; //$NON-NLS-1$

	@Mock
	RevCommit initialCommit;

	@Mock
	Repository db;

	@Mock
	ObjectReader reader;

	@Mock
	private Ref tagRef;

	@Before
	public void setUp() {
		when(db.newObjectReader()).thenReturn(reader);
	}

	@Test
	public void testCreateBranchStartPoint() throws Exception {
		Ref branch = new CreateBranchCommand(db)
				.setName(FROM_INITIAL_BRANCH_NAME).setStartPoint(initialCommit)
				.call();
		assertEquals(initialCommit.getId(), branch.getObjectId());
	}

	@Test
	public void testCreateBranchStartPointAlreadyExisting() throws Exception {
		try {
			new CreateBranchCommand(db).setName(FROM_INITIAL_BRANCH_NAME)
					.setStartPoint(initialCommit).call();
			// fail
		} catch (RefAlreadyExistsException e) {
			// expected
		}
		}

	@Test
	public void testCreateBranchStartPointAlreadyExistingWithForceParameter()
			throws Exception {
		Ref branch = new CreateBranchCommand(db)
				.setName(FROM_INITIAL_BRANCH_NAME).setStartPoint(initialCommit)
				.setForce(true).call();
		assertEquals(initialCommit.getId(), branch.getObjectId());
	}

	@Test
	public void testCreateFromLightweightTag() throws Exception {
		Ref branch = new CreateBranchCommand(db).setName(FROM_LIGHTWEIGHT_TAG)
				.setStartPoint(REFS_TAGS + BRANCH_NAME).call();
		assertEquals(initialCommit.getId(), branch.getObjectId());

	}

	@Test
	public void testCreateFromAnnotatetdTag() throws Exception {
		// Ref tagRef = new TagCommand(db).setName(BRANCH_NAME)
		// .setObjectId(initialCommit)
		// .call();
		Ref branch = new CreateBranchCommand(db).setName(FROM_ANNOTATED_TAG)
				.setStartPoint(REFS_TAGS + BRANCH_NAME).call();
		ObjectId objectId = tagRef.getObjectId();
		assertFalse(objectId == null || objectId.equals(branch.getObjectId()));
		assertEquals(initialCommit.getId(), branch.getObjectId());
	}

	@Test
	public void testCreationImplicitStart() throws Exception {
		new CreateBranchCommand(db).setName(TOPIC_BRANCH_NAME).call();
		assertEquals(db.resolve(HEAD), db.resolve(TOPIC_BRANCH_NAME));
	}

}
