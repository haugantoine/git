/*
 * Copyright (C) 2010, Mathias Kinzler <mathias.kinzler@sap.com>
 * Copyright (C) 2010, Chris Aniszczyk <caniszczyk@gmail.com>
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

import java.io.IOException;
import java.text.MessageFormat;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRefNameException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.RefAlreadyExistsException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Used to create a local branch.
 *
 * @see <a href=
 *      "http://www.kernel.org/pub/software/scm/git/docs/git-branch.html" >Git
 *      documentation about Branch</a>
 */
public class CreateBranchCommand extends GitCommand<Ref> {
	private String name;

	private boolean force = false;

	private SetupUpstreamMode upstreamMode;

	private String startPoint = Constants.HEAD;

	private RevCommit startCommit;

	/**
	 * The modes available for setting up the upstream configuration
	 * (corresponding to the --set-upstream, --track, --no-track options
	 *
	 */
	public enum SetupUpstreamMode {
		/**
		 * Corresponds to the --track option
		 */
		TRACK,
		/**
		 * Corresponds to the --no-track option
		 */
		NOTRACK,
		/**
		 * Corresponds to the --set-upstream option
		 */
		SET_UPSTREAM;
	}

	/**
	 * @param repo
	 */
	protected CreateBranchCommand(Repository repo) {
		super(repo);
	}

	/**
	 * @param name
	 *            the name of the new branch
	 * @return this instance
	 */
	public CreateBranchCommand setName(String name) {
		checkCallable();
		this.name = name;
		return this;
	}

	/**
	 * @param force
	 *            if <code>true</code> and the branch with the given name
	 *            already exists, the start-point of an existing branch will be
	 *            set to a new start-point; if false, the existing branch will
	 *            not be changed
	 * @return this instance
	 */
	public CreateBranchCommand setForce(boolean force) {
		checkCallable();
		this.force = force;
		return this;
	}

	/**
	 * @param startPoint
	 *            corresponds to the start-point option; if <code>null</code>,
	 *            the current HEAD will be used
	 * @return this instance
	 */
	public CreateBranchCommand setStartPoint(String startPoint) {
		checkCallable();
		this.startPoint = startPoint;
		this.startCommit = null;
		return this;
	}

	/**
	 * @param startPoint
	 *            corresponds to the start-point option; if <code>null</code>,
	 *            the current HEAD will be used
	 * @return this instance
	 */
	public CreateBranchCommand setStartPoint(RevCommit startPoint) {
		checkCallable();
		this.startCommit = startPoint;
		this.startPoint = null;
		return this;
	}

	/**
	 * @param mode
	 *            corresponds to the --track/--no-track/--set-upstream options;
	 *            may be <code>null</code>
	 * @return this instance
	 */
	public CreateBranchCommand setUpstreamMode(SetupUpstreamMode mode) {
		checkCallable();
		this.upstreamMode = mode;
		return this;
	}

	/**
	 * @throws RefAlreadyExistsException
	 *             when trying to create (without force) a branch with a name
	 *             that already exists
	 * @throws RefNotFoundException
	 *             if the start point can not be found
	 * @throws InvalidRefNameException
	 *             if the provided name is <code>null</code> or otherwise
	 *             invalid
	 * @return the newly created branch
	 */
	public Ref call() throws GitAPIException, RefAlreadyExistsException,
			RefNotFoundException, InvalidRefNameException {
		checkCallable();
		setCallable(false);
		processOptions();
		try {
			return createBranch();
		} catch (IOException ioe) {
			throw new JGitInternalException(ioe.getMessage(), ioe);
		}
	}

	private void processOptions() throws InvalidRefNameException {
		if (name == null
				|| !Repository.isValidRefName(Constants.R_HEADS + name))
			throw new InvalidRefNameException(
					MessageFormat.format(JGitText.get().branchNameInvalid,
							name == null ? "<null>" : name)); //$NON-NLS-1$
	}

	private Ref createBranch() throws RefAlreadyExistsException,
			RefNotFoundException, IOException {
		boolean exists = isBranchExisting();
		checkBranchCreation(exists);
		ObjectId startAt = getBranchStart();
		String startPointFullName = getStartPointFullName();

		try (RevWalk revWalk = new RevWalk(repo)) {
			if (startPointFullName == null) {
				return createBranchFromCommit(exists, startAt, revWalk);
			} else if (startPointFullName.startsWith(Constants.R_HEADS)
					|| startPointFullName.startsWith(Constants.R_REMOTES)) {
				return createBranchFomBranch(exists, startAt,
						startPointFullName);
			} else {
				return createBranchFromTag(exists, startAt, revWalk,
						startPointFullName);
			}
		}
	}

	private void checkBranchCreation(boolean exists)
			throws RefAlreadyExistsException {
		if (!force && exists)
			throw new RefAlreadyExistsException(MessageFormat
					.format(JGitText.get().refAlreadyExists1, name));
	}

	private boolean isBranchExisting() throws IOException {
		Ref refToCheck = repo.findRef(name);
		return refToCheck != null
				&& refToCheck.getName().startsWith(Constants.R_HEADS);
	}

	private ObjectId getBranchStart() throws AmbiguousObjectException,
			IncorrectObjectTypeException, IOException, RefNotFoundException {
		ObjectId startAt = null;
		if (startCommit == null) {
			String currentStartPoint = getStartPoint();
			startAt = repo.resolve(currentStartPoint);
			if (startAt == null)
				throw new RefNotFoundException(MessageFormat.format(
						JGitText.get().refNotResolved,
						currentStartPoint));
		} else
			startAt = startCommit.getId();
		return startAt;
	}

	private String getStartPointFullName() throws IOException {
		if (startPoint != null) {
			Ref baseRef = repo.findRef(startPoint);
			if (baseRef != null)
				return baseRef.getName();
		}
		return null;
	}

	private Ref createBranchFromCommit(boolean exists, ObjectId startAt,
			RevWalk revWalk)
			throws MissingObjectException, IncorrectObjectTypeException,
			IOException, AmbiguousObjectException {
		return updateBranch(exists, startAt, getBaseCommit(revWalk), "commit"); //$NON-NLS-1$
	}

	private String getBaseCommit(RevWalk revWalk)
			throws MissingObjectException, IncorrectObjectTypeException,
			IOException, AmbiguousObjectException {
		if (startCommit != null)
			return startCommit.getShortMessage();
		ObjectId startPointObjectId = repo.resolve(getStartPoint());
		RevCommit commit = revWalk.parseCommit(startPointObjectId);
		return commit.getShortMessage();
	}

	private String getStartPoint() {
		return startPoint != null ? startPoint : Constants.HEAD;
	}

	private Ref createBranchFromTag(boolean exists, ObjectId startAt,
			RevWalk revWalk, String startPointFullName)
			throws MissingObjectException, IOException {
		startAt = revWalk.peel(revWalk.parseAny(startAt));
		return updateBranch(exists, startAt, startPointFullName, "tag"); //$NON-NLS-1$
	}

	private Ref createBranchFomBranch(boolean exists, ObjectId startAt,
			String startPointFullName) throws IOException {
		Ref result = updateBranch(exists, startAt, startPointFullName,
				"branch"); //$NON-NLS-1$
		if (hasToConfigureBranch(startPointFullName)) {
			configureBranch(startPointFullName);
		}
		return result;
	}

	private Ref updateBranch(boolean exists, ObjectId startAt,
			String startPointFullName, String label) throws IOException {
		String refLogMessage = getRefLogMessage(exists, startPointFullName,
				label); // $NON-NLS-1$
		updateBranchRef(exists, startAt, refLogMessage);
		Ref result = repo.findRef(name);
		if (result == null)
			throw new JGitInternalException(
					JGitText.get().createBranchFailedUnknownReason);
		return result;
	}

	private String getRefLogMessage(boolean exists, String toValue,
			String toLabel) {
		if (exists)
			return "branch: Reset start-point to " + toLabel + " " //$NON-NLS-1$//$NON-NLS-2$
					+ toValue;
		return "branch: Created from " + toLabel + " " //$NON-NLS-1$ //$NON-NLS-2$
				+ toValue;
	}

	private void updateBranchRef(boolean exists, ObjectId startAt,
			String refLogMessage) throws IOException {
		RefUpdate updateRef = repo.updateRef(Constants.R_HEADS + name);
		updateRef.setNewObjectId(startAt);
		updateRef.setRefLogMessage(refLogMessage, false);
		Result updateResult = executeUpdateRef(exists, updateRef);
		if (!isUpdateRefOk(exists, updateResult))
			throw new JGitInternalException(MessageFormat.format(
					JGitText.get().createBranchUnexpectedResult,
					updateResult.name()));
	}

	private Result executeUpdateRef(boolean exists, RefUpdate updateRef)
			throws IOException {
		return (exists && force) ? updateRef.forceUpdate() : updateRef.update();
	}

	private boolean isUpdateRefOk(boolean exists, Result updateResult) {
		return (Result.NEW == updateResult && !exists)
				|| ((Result.NO_CHANGE == updateResult
						|| Result.FAST_FORWARD == updateResult
						|| Result.FORCED == updateResult) && exists);
	}

	private void configureBranch(String baseBranch) throws IOException {
		StoredConfig config = repo.getConfig();

		String remoteName = repo.getRemoteName(baseBranch);
		if (remoteName == null) {
			// set "." as remote
			config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, name,
					ConfigConstants.CONFIG_KEY_REMOTE, "."); //$NON-NLS-1$
			config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, name,
					ConfigConstants.CONFIG_KEY_MERGE, baseBranch);
		} else {
			String branchName = repo.shortenRemoteBranchName(baseBranch);
			config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, name,
					ConfigConstants.CONFIG_KEY_REMOTE, remoteName);
			config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, name,
					ConfigConstants.CONFIG_KEY_MERGE,
					Constants.R_HEADS + branchName);
		}
		config.save();
	}

	private boolean hasToConfigureBranch(String baseBranch) {
		return upstreamMode == SetupUpstreamMode.SET_UPSTREAM
				|| upstreamMode == SetupUpstreamMode.TRACK
				|| (upstreamMode == null
						&& getRepositoryConfigurationRule(baseBranch));
	}

	private boolean getRepositoryConfigurationRule(String baseBranch) {
		// if there was no explicit setting, check the configuration
		String autosetupflag = repo.getConfig().getString(
				ConfigConstants.CONFIG_BRANCH_SECTION, null,
				ConfigConstants.CONFIG_KEY_AUTOSETUPMERGE);
		return "always".equals(autosetupflag) || //$NON-NLS-1$
				(!"false".equals(autosetupflag) //$NON-NLS-1$
						&& baseBranch.startsWith(Constants.R_REMOTES));
	}

}
