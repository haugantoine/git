/*
 * Copyright (C) 2007, Dave Watson <dwatson@mimvista.com>
 * Copyright (C) 2008-2010, Google Inc.
 * Copyright (C) 2006-2010, Robin Rosenberg <robin.rosenberg@dewire.com>
 * Copyright (C) 2006-2012, Shawn O. Pearce <spearce@spearce.org>
 * Copyright (C) 2012, Daniel Megert <daniel_megert@ch.ibm.com>
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

package org.eclipse.jgit.lib;

import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_CORE_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_BARE;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_WORKTREE;
import static org.eclipse.jgit.lib.Constants.DOT_GIT;
import static org.eclipse.jgit.lib.Constants.GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_CEILING_DIRECTORIES_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_DIR_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_INDEX_FILE_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_OBJECT_DIRECTORY_KEY;
import static org.eclipse.jgit.lib.Constants.GIT_WORK_TREE_KEY;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.annotations.Nullable;
import org.eclipse.jgit.attributes.AttributesNodeProvider;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.events.IndexChangedEvent;
import org.eclipse.jgit.events.IndexChangedListener;
import org.eclipse.jgit.events.ListenerList;
import org.eclipse.jgit.events.RepositoryEvent;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FileUtils;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.SystemReader;
import org.eclipse.jgit.util.io.SafeBufferedOutputStream;

/**
 * Represents a Git repository.
 * <p>
 * A repository holds all objects and refs used for managing source code (could
 * be any type of file, but source code is what SCM's are typically used for).
 * <p>
 * This class is thread-safe.
 */
public abstract class Repository implements AutoCloseable {
	private static final ListenerList globalListeners = new ListenerList();

	private static boolean isSymRef(byte[] ref) {
		return ref.length >= 9 //
				&& ref[0] == 'g' //
				&& ref[1] == 'i' //
				&& ref[2] == 't' //
				&& ref[3] == 'd' //
				&& ref[4] == 'i' //
				&& ref[5] == 'r' //
				&& ref[6] == ':' //
				&& ref[7] == ' ';
	}

	private static File getSymRef(File workTree, File dotGit)
			throws IOException {
		byte[] content = IO.readFully(dotGit);
		if (!isSymRef(content))
			throw new IOException(MessageFormat.format(
					JGitText.get().invalidGitdirRef, dotGit.getAbsolutePath()));

		int pathStart = 8;
		int lineEnd = RawParseUtils.nextLF(content, pathStart);
		while (content[lineEnd - 1] == '\n' || (content[lineEnd - 1] == '\r'
				&& SystemReader.getInstance().isWindows()))
			lineEnd--;
		if (lineEnd == pathStart)
			throw new IOException(MessageFormat.format(
					JGitText.get().invalidGitdirRef, dotGit.getAbsolutePath()));

		String gitdirPath = RawParseUtils.decode(content, pathStart, lineEnd);
		File gitdirFile = FS.DETECTED.resolve(workTree, gitdirPath);
		if (gitdirFile.isAbsolute())
			return gitdirFile;
		return new File(workTree, gitdirPath).getCanonicalFile();
	}

	public static File getAutomagicallyDetectGitDirectory(File d) {
		return findGitDirectory(d, null);
	}

	/**
	 * Configure {@code GIT_DIR} by searching up the file system.
	 * <p>
	 * Starts from the supplied directory path and scans up through the parent
	 * directory tree until a Git repository is found. Success can be determined
	 * by checking for {@code getGitDir() != null}.
	 * <p>
	 * The search can be limited to specific spaces of the local filesystem by
	 * {@link #addCeilingDirectory(File)}, or inheriting the list through a
	 * prior call to {@link #readEnvironment()}.
	 *
	 * @param current
	 *            directory to begin searching in.
	 * @return {@code this} (for chaining calls).
	 */
	public static File findGitDirectory(File current,
			List<File> ceilingDirectories) {
		while (current != null) {
			File dir = new File(current, DOT_GIT);
			if (FileKey.isGitRepository(dir)) {
				return dir;
			} else if (dir.isFile()) {
				try {
					return getSymRef(current, dir);
				} catch (IOException ignored) {
					// Continue searching if gitdir ref isn't found
				}
			} else if (FileKey.isGitRepository(current)) {
				return current;
			}

			current = current.getParentFile();
			if (current != null && ceilingDirectories != null
					&& ceilingDirectories.contains(current))
				return null;
		}
		return null;
	}

	public static File getGitDir(File dir) {
		RepositoryBuilder rb = new RepositoryBuilder(); //
		rb.setGitDir(dir);
		readEnvironment(rb); //
		if (rb.getGitDir() == null)
			findGitDir(new File("").getAbsoluteFile(), rb); //$NON-NLS-1$
		return rb.getGitDir();
	}

	public static Repository createRepository(File directory, File gitDir,
			boolean bare) throws IOException {
		RepositoryBuilder builder = new RepositoryBuilder();
		readEnvironment(builder);
		if (gitDir == null)
			gitDir = builder.getGitDir();
		else
			builder.setGitDir(gitDir);
		if (bare) {
			builder.setBare();
			if (directory != null) {
				builder.setGitDir(directory);
			} else if (builder.getGitDir() == null) {
				builder.setGitDir(getUserDirectory());
			}
		} else {
			if (directory == null) {
				if (builder.getGitDir() == null) {
					File d = new File(getUserDirectory(), Constants.DOT_GIT);
					builder.setGitDir(d);
				} else {
					builder.setWorkTree(getUserDirectory());
				}
			} else {
				builder.setWorkTree(directory);
				if (gitDir == null)
					builder.setGitDir(new File(directory, Constants.DOT_GIT));
			}
		}
		setup(builder);
		return new FileRepository(builder);
	}

	private static File getUserDirectory() {
		String dStr = SystemReader.getInstance()
				.getProperty("user.dir"); //$NON-NLS-1$
		if (dStr == null)
			dStr = "."; //$NON-NLS-1$
		return new File(dStr);
	}

	public static Repository createRepositoryMustExist(File dir)
			throws IOException {
		RepositoryBuilder builder = new RepositoryBuilder();
		builder.setWorkTree(dir);
		findGitDir(dir, builder);
		setup(builder);
		Repository repo = new FileRepository(builder);
		if (!repo.getObjectDatabase().exists())
			throw new RepositoryNotFoundException(builder.getGitDir());
		return repo;
	}

	public static Repository createEmptyRepository() throws IOException {
		RepositoryBuilder builder = new RepositoryBuilder();
		setup(builder);
		return new FileRepository(builder);
	}

	public static Repository createRepository(File gitdir, File workTree)
			throws IOException {
		RepositoryBuilder rb = new RepositoryBuilder();
		rb.setGitDir(gitdir);
		rb.setWorkTree(workTree);
		setup(rb);
		return new FileRepository(rb);
	}

	public static Repository createGitDirEnvRepository(File file)
			throws IOException {
		RepositoryBuilder rb = new RepositoryBuilder(); //
		rb.setGitDir(file); //
		readEnvironment(rb); //
		if (rb.getGitDir() == null)
			findGitDir(new File("").getAbsoluteFile(), rb); //$NON-NLS-1$
		if (rb.getGitDir() == null)
			throw new IOException();
		setup(rb);
		return new FileRepository(rb);
	}

	public static Repository createFileRepository(File file)
			throws IOException {
		RepositoryBuilder rb = new RepositoryBuilder(); //
		rb.setGitDir(file);
		setup(rb);
		Repository repo = new FileRepository(rb);
		if (!repo.getObjectDatabase().exists())
			throw new RepositoryNotFoundException(rb.getGitDir());
		return repo;
	}

	public static Repository createGitDirRepository(File dir)
			throws IOException {
		RepositoryBuilder rb = new RepositoryBuilder(); //
		rb.setGitDir(dir);
		setup(rb);
		return new FileRepository(rb);
	}

	public static Repository createWorkTreeRepository(File dir)
			throws IOException {
		RepositoryBuilder builder = new RepositoryBuilder();
		builder.setWorkTree(dir);
		setup(builder);
		Repository repo = new FileRepository(builder);
		if (!repo.getObjectDatabase().exists())
			throw new RepositoryNotFoundException(builder.getGitDir());
		return repo;
	}

	public static Repository createWorkTreeRepository2(File worktree)
			throws IOException {
		RepositoryBuilder builder = new RepositoryBuilder();
		builder.setWorkTree(worktree);
		setup(builder);
		return new FileRepository(builder);
	}

	public static Repository createGitDirRepo(File dir) throws IOException {
		RepositoryBuilder rb = new RepositoryBuilder();
		if (RepositoryCache.FileKey.isGitRepository(dir))
			rb.setGitDir(dir);
		else
			findGitDir(dir, rb);

		setup(rb);
		return new FileRepository(rb);
	}

	public static FileRepository createRepository(File indexFile, File objDir,
			File altObjDir, File theDir) throws IOException {
		RepositoryBuilder r = new RepositoryBuilder(); //
		r.setGitDir(theDir);//
		r.setObjectDirectory(objDir); //
		r.addAlternateObjectDirectory(altObjDir); //
		r.setIndexFile(indexFile); //
		setup(r);
		return new FileRepository(r);
	}

	/** @return the global listener list observing all events in this JVM. */
	public static ListenerList getGlobalListenerList() {
		return globalListeners;
	}

	private final AtomicInteger useCnt = new AtomicInteger(1);

	/** Metadata directory holding the repository's critical files. */
	private final File gitDir;

	private final ListenerList myListeners = new ListenerList();

	/** If not bare, the top level directory of the working files. */
	private File workTree;

	/** If not bare, the index file caching the working file states. */
	private final File indexFile;

	/**
	 * Initialize a new repository instance.
	 *
	 * @param options
	 *            options to configure the repository.
	 */
	protected Repository() {
		this(null, null, null);
	}

	/**
	 * Initialize a new repository instance.
	 *
	 * @param options
	 *            options to configure the repository.
	 */
	protected Repository(File gitDir, File workTree, File indexFile) {
		this.gitDir = gitDir;
		this.workTree = workTree;
		this.indexFile = indexFile;
	}

	/** @return listeners observing only events on this repository. */
	@NonNull
	public ListenerList getListenerList() {
		return myListeners;
	}

	/**
	 * Fire an event to all registered listeners.
	 * <p>
	 * The source repository of the event is automatically set to this
	 * repository, before the event is delivered to any listeners.
	 *
	 * @param event
	 *            the event to deliver.
	 */
	public void fireEvent(RepositoryEvent<?> event) {
		event.setRepository(this);
		myListeners.dispatch(event);
		globalListeners.dispatch(event);
	}

	/**
	 * Create a new Git repository.
	 * <p>
	 * Repository with working tree is created using this method. This method is
	 * the same as {@code create(false)}.
	 *
	 * @throws IOException
	 * @see #create(boolean)
	 */
	public void create() throws IOException {
		create(false);
	}

	/**
	 * Create a new Git repository initializing the necessary files and
	 * directories.
	 *
	 * @param bare
	 *            if true, a bare repository (a repository without a working
	 *            directory) is created.
	 * @throws IOException
	 *             in case of IO problem
	 */
	public abstract void create(boolean bare) throws IOException;

	/**
	 * @return local metadata directory; {@code null} if repository isn't local.
	 */
	/*
	 * TODO This method should be annotated as Nullable, because in some
	 * specific configurations metadata is not located in the local file system
	 * (for example in memory databases). In "usual" repositories this
	 * annotation would only cause compiler errors at places where the actual
	 * directory can never be null.
	 */
	public File getDirectory() {
		return gitDir;
	}

	/**
	 * @return the object database which stores this repository's data.
	 */
	@NonNull
	public abstract ObjectDatabase getObjectDatabase();

	/**
	 * @return a new inserter to create objects in {@link #getObjectDatabase()}
	 */
	@NonNull
	public ObjectInserter newObjectInserter() {
		return getObjectDatabase().newInserter();
	}

	/**
	 * @return a new reader to read objects from {@link #getObjectDatabase()}
	 */
	@NonNull
	public ObjectReader newObjectReader() {
		return getObjectDatabase().newReader();
	}

	/** @return the reference database which stores the reference namespace. */
	@NonNull
	public abstract RefDatabase getRefDatabase();

	/**
	 * @return the configuration of this repository
	 */
	@NonNull
	public abstract StoredConfig getConfig();

	/**
	 * @return a new {@link AttributesNodeProvider}. This
	 *         {@link AttributesNodeProvider} is lazy loaded only once. It means
	 *         that it will not be updated after loading. Prefer creating new
	 *         instance for each use.
	 * @since 4.2
	 */
	@NonNull
	public abstract AttributesNodeProvider createAttributesNodeProvider();

	/**
	 * @param objectId
	 * @return true if the specified object is stored in this repo or any of the
	 *         known shared repositories.
	 */
	public boolean hasObject(AnyObjectId objectId) {
		try {
			return getObjectDatabase().has(objectId);
		} catch (IOException e) {
			// Legacy API, assume error means "no"
			return false;
		}
	}

	/**
	 * Open an object from this repository.
	 * <p>
	 * This is a one-shot call interface which may be faster than allocating a
	 * {@link #newObjectReader()} to perform the lookup.
	 *
	 * @param objectId
	 *            identity of the object to open.
	 * @return a {@link ObjectLoader} for accessing the object.
	 * @throws MissingObjectException
	 *             the object does not exist.
	 * @throws IOException
	 *             the object store cannot be accessed.
	 */
	@NonNull
	public ObjectLoader open(final AnyObjectId objectId)
			throws MissingObjectException, IOException {
		return getObjectDatabase().open(objectId);
	}

	/**
	 * Open an object from this repository.
	 * <p>
	 * This is a one-shot call interface which may be faster than allocating a
	 * {@link #newObjectReader()} to perform the lookup.
	 *
	 * @param objectId
	 *            identity of the object to open.
	 * @param typeHint
	 *            hint about the type of object being requested, e.g.
	 *            {@link Constants#OBJ_BLOB}; {@link ObjectReader#OBJ_ANY} if
	 *            the object type is not known, or does not matter to the
	 *            caller.
	 * @return a {@link ObjectLoader} for accessing the object.
	 * @throws MissingObjectException
	 *             the object does not exist.
	 * @throws IncorrectObjectTypeException
	 *             typeHint was not OBJ_ANY, and the object's actual type does
	 *             not match typeHint.
	 * @throws IOException
	 *             the object store cannot be accessed.
	 */
	@NonNull
	public ObjectLoader open(AnyObjectId objectId, int typeHint)
			throws MissingObjectException, IncorrectObjectTypeException,
			IOException {
		return getObjectDatabase().open(objectId, typeHint);
	}

	/**
	 * Create a command to update, create or delete a ref in this repository.
	 *
	 * @param ref
	 *            name of the ref the caller wants to modify.
	 * @return an update command. The caller must finish populating this command
	 *         and then invoke one of the update methods to actually make a
	 *         change.
	 * @throws IOException
	 *             a symbolic ref was passed in and could not be resolved back
	 *             to the base ref, as the symbolic ref could not be read.
	 */
	@NonNull
	public RefUpdate updateRef(final String ref) throws IOException {
		return updateRef(ref, false);
	}

	/**
	 * Create a command to update, create or delete a ref in this repository.
	 *
	 * @param ref
	 *            name of the ref the caller wants to modify.
	 * @param detach
	 *            true to create a detached head
	 * @return an update command. The caller must finish populating this command
	 *         and then invoke one of the update methods to actually make a
	 *         change.
	 * @throws IOException
	 *             a symbolic ref was passed in and could not be resolved back
	 *             to the base ref, as the symbolic ref could not be read.
	 */
	@NonNull
	public RefUpdate updateRef(final String ref, final boolean detach)
			throws IOException {
		return getRefDatabase().newUpdate(ref, detach);
	}

	/**
	 * Create a command to rename a ref in this repository
	 *
	 * @param fromRef
	 *            name of ref to rename from
	 * @param toRef
	 *            name of ref to rename to
	 * @return an update command that knows how to rename a branch to another.
	 * @throws IOException
	 *             the rename could not be performed.
	 *
	 */
	@NonNull
	public RefRename renameRef(final String fromRef, final String toRef)
			throws IOException {
		return getRefDatabase().newRename(fromRef, toRef);
	}

	/**
	 * Parse a git revision string and return an object id.
	 *
	 * Combinations of these operators are supported:
	 * <ul>
	 * <li><b>HEAD</b>, <b>MERGE_HEAD</b>, <b>FETCH_HEAD</b></li>
	 * <li><b>SHA-1</b>: a complete or abbreviated SHA-1</li>
	 * <li><b>refs/...</b>: a complete reference name</li>
	 * <li><b>short-name</b>: a short reference name under {@code refs/heads},
	 * {@code refs/tags}, or {@code refs/remotes} namespace</li>
	 * <li><b>tag-NN-gABBREV</b>: output from describe, parsed by treating
	 * {@code ABBREV} as an abbreviated SHA-1.</li>
	 * <li><i>id</i><b>^</b>: first parent of commit <i>id</i>, this is the same
	 * as {@code id^1}</li>
	 * <li><i>id</i><b>^0</b>: ensure <i>id</i> is a commit</li>
	 * <li><i>id</i><b>^n</b>: n-th parent of commit <i>id</i></li>
	 * <li><i>id</i><b>~n</b>: n-th historical ancestor of <i>id</i>, by first
	 * parent. {@code id~3} is equivalent to {@code id^1^1^1} or {@code id^^^}.
	 * </li>
	 * <li><i>id</i><b>:path</b>: Lookup path under tree named by <i>id</i></li>
	 * <li><i>id</i><b>^{commit}</b>: ensure <i>id</i> is a commit</li>
	 * <li><i>id</i><b>^{tree}</b>: ensure <i>id</i> is a tree</li>
	 * <li><i>id</i><b>^{tag}</b>: ensure <i>id</i> is a tag</li>
	 * <li><i>id</i><b>^{blob}</b>: ensure <i>id</i> is a blob</li>
	 * </ul>
	 *
	 * <p>
	 * The following operators are specified by Git conventions, but are not
	 * supported by this method:
	 * <ul>
	 * <li><b>ref@{n}</b>: n-th version of ref as given by its reflog</li>
	 * <li><b>ref@{time}</b>: value of ref at the designated time</li>
	 * </ul>
	 *
	 * @param revstr
	 *            A git object references expression
	 * @return an ObjectId or {@code null} if revstr can't be resolved to any
	 *         ObjectId
	 * @throws AmbiguousObjectException
	 *             {@code revstr} contains an abbreviated ObjectId and this
	 *             repository contains more than one object which match to the
	 *             input abbreviation.
	 * @throws IncorrectObjectTypeException
	 *             the id parsed does not meet the type required to finish
	 *             applying the operators in the expression.
	 * @throws RevisionSyntaxException
	 *             the expression is not supported by this implementation, or
	 *             does not meet the standard syntax.
	 * @throws IOException
	 *             on serious errors
	 */
	@Nullable
	public ObjectId resolve(final String revstr)
			throws AmbiguousObjectException, IncorrectObjectTypeException,
			RevisionSyntaxException, IOException {
		try (RevWalk rw = new RevWalk(this)) {
			Object resolved = resolve(rw, revstr);
			if (resolved instanceof String) {
				final Ref ref = findRef((String) resolved);
				return ref != null ? ref.getLeaf().getObjectId() : null;
			} else {
				return (ObjectId) resolved;
			}
		}
	}

	/**
	 * Simplify an expression, but unlike {@link #resolve(String)} it will not
	 * resolve a branch passed or resulting from the expression, such as @{-}.
	 * Thus this method can be used to process an expression to a method that
	 * expects a branch or revision id.
	 *
	 * @param revstr
	 * @return object id or ref name from resolved expression or {@code null} if
	 *         given expression cannot be resolved
	 * @throws AmbiguousObjectException
	 * @throws IOException
	 */
	@Nullable
	public String simplify(final String revstr)
			throws AmbiguousObjectException, IOException {
		try (RevWalk rw = new RevWalk(this)) {
			Object resolved = resolve(rw, revstr);
			if (resolved == null) {
				return null;
			}
			if (resolved instanceof String)
				return (String) resolved;
			return ((AnyObjectId) resolved).getName();
		}
	}

	@Nullable
	private Object resolve(final RevWalk rw, final String revstr)
			throws IOException {
		char[] revChars = revstr.toCharArray();
		RevObject rev = null;
		String name = null;
		int done = 0;
		for (int resolveIndex = 0; resolveIndex < revChars.length; ++resolveIndex) {
			switch (revChars[resolveIndex]) {
			case '^':
				if (rev == null) {
					if (name == null)
						if (done == 0)
							name = new String(revChars, done, resolveIndex);
						else {
							done = resolveIndex + 1;
							break;
						}
					rev = parseSimple(rw, name);
					name = null;
					if (rev == null)
						return null;
				}
				if (resolveIndex + 1 < revChars.length) {
					switch (revChars[resolveIndex + 1]) {
					case '0':
					case '1':
					case '2':
					case '3':
					case '4':
					case '5':
					case '6':
					case '7':
					case '8':
					case '9':
						int parentCommitIndex;
						rev = rw.parseCommit(rev);
						for (parentCommitIndex = resolveIndex
								+ 1; parentCommitIndex < revChars.length; ++parentCommitIndex) {
							if (!Character.isDigit(revChars[parentCommitIndex]))
								break;
						}
						String parentnum = new String(revChars,
								resolveIndex + 1,
								parentCommitIndex - resolveIndex - 1);
						int pnum;
						try {
							pnum = Integer.parseInt(parentnum);
						} catch (NumberFormatException e) {
							throw new RevisionSyntaxException(
									JGitText.get().invalidCommitParentNumber,
									revstr);
						}
						if (pnum != 0) {
							RevCommit commit = (RevCommit) rev;
							if (pnum > commit.getParentCount())
								rev = null;
							else
								rev = commit.getParent(pnum - 1);
						}
						resolveIndex = parentCommitIndex - 1;
						done = parentCommitIndex;
						break;
					case '{':
						int typeIndex;
						String item = null;
						for (typeIndex = resolveIndex
								+ 2; typeIndex < revChars.length; ++typeIndex) {
							if (revChars[typeIndex] == '}') {
								item = new String(revChars, resolveIndex + 2,
										typeIndex - resolveIndex - 2);
								break;
							}
						}
						resolveIndex = typeIndex;
						if (item == null)
							throw new RevisionSyntaxException(revstr);
						if (item.equals("tree")) { //$NON-NLS-1$
							rev = rw.parseTree(rev);
						} else if (item.equals("commit")) { //$NON-NLS-1$
							rev = rw.parseCommit(rev);
						} else if (item.equals("blob")) { //$NON-NLS-1$
							rev = rw.peel(rev);
							if (!(rev instanceof RevBlob))
								throw new IncorrectObjectTypeException(rev,
										Constants.TYPE_BLOB);
						} else if (item.equals("")) { //$NON-NLS-1$
							rev = rw.peel(rev);
						} else
							throw new RevisionSyntaxException(revstr);
						done = typeIndex;
						break;
					default:
						rev = getRevisionWalkCurrentCommit(rw, rev);
					}
				} else {
					rev = getRevisionWalkCurrentCommit(rw, rev);
				}
				done = resolveIndex + 1;
				break;
			case '~':
				if (rev == null) {
					if (name == null)
						if (done == 0)
							name = new String(revChars, done, resolveIndex);
						else {
							done = resolveIndex + 1;
							break;
						}
					rev = parseSimple(rw, name);
					name = null;
					if (rev == null)
						return null;
				}
				rev = rw.peel(rev);
				if (!(rev instanceof RevCommit))
					throw new IncorrectObjectTypeException(rev,
							Constants.TYPE_COMMIT);
				int l;
				for (l = resolveIndex + 1; l < revChars.length; ++l) {
					if (!Character.isDigit(revChars[l]))
						break;
				}
				int dist;
				if (l - resolveIndex > 1) {
					String distnum = new String(revChars, resolveIndex + 1,
							l - resolveIndex - 1);
					try {
						dist = Integer.parseInt(distnum);
					} catch (NumberFormatException e) {
						throw new RevisionSyntaxException(
								JGitText.get().invalidAncestryLength, revstr);
					}
				} else
					dist = 1;
				while (dist > 0) {
					RevCommit commit = (RevCommit) rev;
					if (commit.getParentCount() == 0) {
						rev = null;
						break;
					}
					commit = commit.getParent(0);
					rw.parseHeaders(commit);
					rev = commit;
					--dist;
				}
				resolveIndex = l - 1;
				done = l;
				break;
			case '@':
				if (rev != null)
					throw new RevisionSyntaxException(revstr);
				if (resolveIndex + 1 < revChars.length
						&& revChars[resolveIndex + 1] != '{')
					continue;
				int m;
				String time = null;
				for (m = resolveIndex + 2; m < revChars.length; ++m) {
					if (revChars[m] == '}') {
						time = new String(revChars, resolveIndex + 2,
								m - resolveIndex - 2);
						break;
					}
				}
				if (time == null)
					throw new RevisionSyntaxException(revstr);
				if (time.equals("upstream")) { //$NON-NLS-1$
					if (name == null)
						name = new String(revChars, done, resolveIndex);
					if (name.equals("")) //$NON-NLS-1$
						// Currently checked out branch, HEAD if
						// detached
						name = Constants.HEAD;
					if (!Repository.isValidRefName("x/" + name)) //$NON-NLS-1$
						throw new RevisionSyntaxException(revstr);
					Ref ref = findRef(name);
					name = null;
					if (ref == null)
						return null;
					if (ref.isSymbolic())
						ref = ref.getLeaf();
					name = ref.getName();

					RemoteConfig remoteConfig;
					try {
						remoteConfig = new RemoteConfig(getConfig(), "origin"); //$NON-NLS-1$
					} catch (URISyntaxException e) {
						throw new RevisionSyntaxException(revstr);
					}
					String remoteBranchName = getConfig().getString(
							ConfigConstants.CONFIG_BRANCH_SECTION,
							Repository.shortenRefName(ref.getName()),
							ConfigConstants.CONFIG_KEY_MERGE);
					List<RefSpec> fetchRefSpecs = remoteConfig
							.getFetchRefSpecs();
					for (RefSpec refSpec : fetchRefSpecs) {
						if (refSpec.matchSource(remoteBranchName)) {
							RefSpec expandFromSource = refSpec
									.expandFromSource(remoteBranchName);
							name = expandFromSource.getDestination();
							break;
						}
					}
					if (name == null)
						throw new RevisionSyntaxException(revstr);
				} else if (time.matches("^-\\d+$")) { //$NON-NLS-1$
					if (name != null)
						throw new RevisionSyntaxException(revstr);
					String previousCheckout = resolveReflogCheckout(
							-Integer.parseInt(time));
					if (ObjectId.isId(previousCheckout))
						rev = parseSimple(rw, previousCheckout);
					else
						name = previousCheckout;
				} else {
					if (name == null)
						name = new String(revChars, done, resolveIndex);
					if (name.equals("")) //$NON-NLS-1$
						name = Constants.HEAD;
					if (!Repository.isValidRefName("x/" + name)) //$NON-NLS-1$
						throw new RevisionSyntaxException(revstr);
					Ref ref = findRef(name);
					name = null;
					if (ref == null)
						return null;
					// @{n} means current branch, not HEAD@{1} unless
					// detached
					if (ref.isSymbolic())
						ref = ref.getLeaf();
					rev = resolveReflog(rw, ref, time);
				}
				resolveIndex = m;
				break;
			case ':': {
				RevTree tree;
				if (rev == null) {
					if (name == null)
						name = new String(revChars, done, resolveIndex);
					if (name.equals("")) //$NON-NLS-1$
						name = Constants.HEAD;
					rev = parseSimple(rw, name);
					name = null;
				}
				if (rev == null)
					return null;
				tree = rw.parseTree(rev);
				if (resolveIndex == revChars.length - 1)
					return tree.copy();

				TreeWalk tw = TreeWalk
						.forPath(rw.getObjectReader(),
								new String(revChars, resolveIndex + 1,
										revChars.length - resolveIndex - 1),
								tree);
				return tw != null ? tw.getObjectId(0) : null;
			}
			default:
				if (rev != null)
					throw new RevisionSyntaxException(revstr);
			}
		}
		if (rev != null)
			return rev.copy();
		if (name != null)
			return name;
		if (done == revstr.length())
			return null;
		name = revstr.substring(done);
		if (!Repository.isValidRefName("x/" + name)) //$NON-NLS-1$
			throw new RevisionSyntaxException(revstr);
		if (findRef(name) != null)
			return name;
		return resolveSimple(name);
	}

	private RevObject getRevisionWalkCurrentCommit(final RevWalk rw,
			RevObject rev) throws MissingObjectException, IOException,
			IncorrectObjectTypeException {
		rev = rw.peel(rev);
		if (!(rev instanceof RevCommit))
			throw new IncorrectObjectTypeException(rev, Constants.TYPE_COMMIT);
		RevCommit commit = ((RevCommit) rev);
		if (commit.getParentCount() == 0)
			rev = null;
		else
			rev = commit.getParent(0);
		return rev;
	}

	private static boolean isHex(char c) {
		return ('0' <= c && c <= '9') //
				|| ('a' <= c && c <= 'f') //
				|| ('A' <= c && c <= 'F');
	}

	private static boolean isAllHex(String str, int ptr) {
		while (ptr < str.length()) {
			if (!isHex(str.charAt(ptr++)))
				return false;
		}
		return true;
	}

	@Nullable
	private RevObject parseSimple(RevWalk rw, String revstr)
			throws IOException {
		ObjectId id = resolveSimple(revstr);
		return id != null ? rw.parseAny(id) : null;
	}

	@Nullable
	private ObjectId resolveSimple(final String revstr) throws IOException {
		if (ObjectId.isId(revstr))
			return ObjectId.fromString(revstr);

		if (Repository.isValidRefName("x/" + revstr)) { //$NON-NLS-1$
			Ref r = getRefDatabase().getRef(revstr);
			if (r != null)
				return r.getObjectId();
		}

		if (AbbreviatedObjectId.isId(revstr))
			return resolveAbbreviation(revstr);

		int dashg = revstr.indexOf("-g"); //$NON-NLS-1$
		if ((dashg + 5) < revstr.length() && 0 <= dashg
				&& isHex(revstr.charAt(dashg + 2))
				&& isHex(revstr.charAt(dashg + 3))
				&& isAllHex(revstr, dashg + 4)) {
			// Possibly output from git describe?
			String s = revstr.substring(dashg + 2);
			if (AbbreviatedObjectId.isId(s))
				return resolveAbbreviation(s);
		}

		return null;
	}

	@Nullable
	private String resolveReflogCheckout(int checkoutNo) throws IOException {
		ReflogReader reader = getReflogReader(Constants.HEAD);
		if (reader == null) {
			return null;
		}
		List<ReflogEntry> reflogEntries = reader.getReverseEntries();
		for (ReflogEntry entry : reflogEntries) {
			CheckoutEntry checkout = entry.parseCheckout();
			if (checkout != null && checkoutNo-- == 1)
				return checkout.getFromBranch();
		}
		return null;
	}

	private RevCommit resolveReflog(RevWalk rw, Ref ref, String time)
			throws IOException {
		int number;
		try {
			number = Integer.parseInt(time);
		} catch (NumberFormatException nfe) {
			throw new RevisionSyntaxException(MessageFormat
					.format(JGitText.get().invalidReflogRevision, time));
		}
		assert number >= 0;
		ReflogReader reader = getReflogReader(ref.getName());
		if (reader == null) {
			throw new RevisionSyntaxException(
					MessageFormat.format(JGitText.get().reflogEntryNotFound,
							Integer.valueOf(number), ref.getName()));
		}
		ReflogEntry entry = reader.getReverseEntry(number);
		if (entry == null)
			throw new RevisionSyntaxException(
					MessageFormat.format(JGitText.get().reflogEntryNotFound,
							Integer.valueOf(number), ref.getName()));

		return rw.parseCommit(entry.getNewId());
	}

	@Nullable
	private ObjectId resolveAbbreviation(final String revstr)
			throws IOException, AmbiguousObjectException {
		AbbreviatedObjectId id = AbbreviatedObjectId.fromString(revstr);
		try (ObjectReader reader = newObjectReader()) {
			Collection<ObjectId> matches = reader.resolve(id);
			if (matches.size() == 0)
				return null;
			if (matches.size() == 1)
				return matches.iterator().next();
			throw new AmbiguousObjectException(id, matches);
		}
	}

	/**
	 * Increment the use counter by one, requiring a matched {@link #close()}.
	 */
	public void incrementOpen() {
		useCnt.incrementAndGet();
	}

	/** Decrement the use count, and maybe close resources. */
	public void close() {
		if (useCnt.decrementAndGet() == 0) {
			doClose();
		}
	}

	/**
	 * Invoked when the use count drops to zero during {@link #close()}.
	 * <p>
	 * The default implementation closes the object and ref databases.
	 */
	protected void doClose() {
		getObjectDatabase().close();
		getRefDatabase().close();
	}

	@NonNull
	@SuppressWarnings("nls")
	public String toString() {
		String desc;
		File directory = getDirectory();
		if (directory == null)
			desc = getClass().getSimpleName() + "-" //$NON-NLS-1$
					+ System.identityHashCode(this);
		else
			desc = directory.getPath();
		return "Repository[" + desc + "]"; //$NON-NLS-1$
	}

	/**
	 * Get the name of the reference that {@code HEAD} points to.
	 * <p>
	 * This is essentially the same as doing:
	 *
	 * <pre>
	 * return getRef(Constants.HEAD).getTarget().getName()
	 * </pre>
	 *
	 * Except when HEAD is detached, in which case this method returns the
	 * current ObjectId in hexadecimal string format.
	 *
	 * @return name of current branch (for example {@code refs/heads/master}),
	 *         an ObjectId in hex format if the current branch is detached, or
	 *         {@code null} if the repository is corrupt and has no HEAD
	 *         reference.
	 * @throws IOException
	 */
	@Nullable
	public String getFullBranch() throws IOException {
		Ref head = findRef(Constants.HEAD);
		if (head == null) {
			return null;
		}
		if (head.isSymbolic()) {
			return head.getTarget().getName();
		}
		ObjectId objectId = head.getObjectId();
		if (objectId != null) {
			return objectId.name();
		}
		return null;
	}

	/**
	 * Get the short name of the current branch that {@code HEAD} points to.
	 * <p>
	 * This is essentially the same as {@link #getFullBranch()}, except the
	 * leading prefix {@code refs/heads/} is removed from the reference before
	 * it is returned to the caller.
	 *
	 * @return name of current branch (for example {@code master}), an ObjectId
	 *         in hex format if the current branch is detached, or {@code null}
	 *         if the repository is corrupt and has no HEAD reference.
	 * @throws IOException
	 */
	@Nullable
	public String getBranch() throws IOException {
		String name = getFullBranch();
		if (name != null)
			return shortenRefName(name);
		return null;
	}

	/**
	 * Objects known to exist but not expressed by {@link #getAllRefs()}.
	 * <p>
	 * When a repository borrows objects from another repository, it can
	 * advertise that it safely has that other repository's references, without
	 * exposing any other details about the other repository. This may help a
	 * client trying to push changes avoid pushing more than it needs to.
	 *
	 * @return unmodifiable collection of other known objects.
	 */
	@NonNull
	public Set<ObjectId> getAdditionalHaves() {
		return Collections.emptySet();
	}

	/**
	 * Get a ref by name.
	 *
	 * @param name
	 *            the name of the ref to lookup. Must not be a short-hand form;
	 *            e.g., "master" is not automatically expanded to
	 *            "refs/heads/master".
	 * @return the Ref with the given name, or {@code null} if it does not exist
	 * @throws IOException
	 * @since 4.2
	 */
	@Nullable
	public Ref exactRef(String name) throws IOException {
		return getRefDatabase().exactRef(name);
	}

	/**
	 * Search for a ref by (possibly abbreviated) name.
	 *
	 * @param name
	 *            the name of the ref to lookup. May be a short-hand form, e.g.
	 *            "master" which is is automatically expanded to
	 *            "refs/heads/master" if "refs/heads/master" already exists.
	 * @return the Ref with the given name, or {@code null} if it does not exist
	 * @throws IOException
	 * @since 4.2
	 */
	@Nullable
	public Ref findRef(String name) throws IOException {
		return getRefDatabase().getRef(name);
	}

	/**
	 * @return mutable map of all known refs (heads, tags, remotes).
	 */
	@NonNull
	public Map<String, Ref> getAllRefs() {
		try {
			return getRefDatabase().getRefs(RefDatabase.ALL);
		} catch (IOException e) {
			return new HashMap<String, Ref>();
		}
	}

	/**
	 * @return mutable map of all tags; key is short tag name ("v1.0") and value
	 *         of the entry contains the ref with the full tag name
	 *         ("refs/tags/v1.0").
	 */
	@NonNull
	public Map<String, Ref> getTags() {
		try {
			return getRefDatabase().getRefs(Constants.R_TAGS);
		} catch (IOException e) {
			return new HashMap<String, Ref>();
		}
	}

	/**
	 * Peel a possibly unpeeled reference to an annotated tag.
	 * <p>
	 * If the ref cannot be peeled (as it does not refer to an annotated tag)
	 * the peeled id stays null, but {@link Ref#isPeeled()} will be true.
	 *
	 * @param ref
	 *            The ref to peel
	 * @return <code>ref</code> if <code>ref.isPeeled()</code> is true; else a
	 *         new Ref object representing the same data as Ref, but isPeeled()
	 *         will be true and getPeeledObjectId will contain the peeled object
	 *         (or null).
	 */
	@NonNull
	public Ref peel(final Ref ref) {
		try {
			return getRefDatabase().peel(ref);
		} catch (IOException e) {
			// Historical accident; if the reference cannot be peeled due
			// to some sort of repository access problem we claim that the
			// same as if the reference was not an annotated tag.
			return ref;
		}
	}

	/**
	 * @return a map with all objects referenced by a peeled ref.
	 */
	@NonNull
	public Map<AnyObjectId, Set<Ref>> getAllRefsByPeeledObjectId() {
		Map<String, Ref> allRefs = getAllRefs();
		Map<AnyObjectId, Set<Ref>> ret = new HashMap<AnyObjectId, Set<Ref>>(
				allRefs.size());
		for (Ref ref : allRefs.values()) {
			ref = peel(ref);
			AnyObjectId target = ref.getPeeledObjectId();
			if (target == null)
				target = ref.getObjectId();
			// We assume most Sets here are singletons
			Set<Ref> oset = ret.put(target, Collections.singleton(ref));
			if (oset != null) {
				// that was not the case (rare)
				if (oset.size() == 1) {
					// Was a read-only singleton, we must copy to a new Set
					oset = new HashSet<Ref>(oset);
				}
				ret.put(target, oset);
				oset.add(ref);
			}
		}
		return ret;
	}

	/**
	 * @return the index file location or {@code null} if repository isn't
	 *         local.
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 */
	@NonNull
	public File getIndexFile() throws NoWorkTreeException {
		if (isBare())
			throw new NoWorkTreeException();
		return indexFile;
	}

	/**
	 * Create a new in-core index representation and read an index from disk.
	 * <p>
	 * The new index will be read before it is returned to the caller. Read
	 * failures are reported as exceptions and therefore prevent the method from
	 * returning a partially populated index.
	 *
	 * @return a cache representing the contents of the specified index file (if
	 *         it exists) or an empty cache if the file does not exist.
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 * @throws IOException
	 *             the index file is present but could not be read.
	 * @throws CorruptObjectException
	 *             the index file is using a format or extension that this
	 *             library does not support.
	 */
	@NonNull
	public DirCache readDirCache()
			throws NoWorkTreeException, CorruptObjectException, IOException {
		return DirCache.read(this);
	}

	/**
	 * Create a new in-core index representation, lock it, and read from disk.
	 * <p>
	 * The new index will be locked and then read before it is returned to the
	 * caller. Read failures are reported as exceptions and therefore prevent
	 * the method from returning a partially populated index.
	 *
	 * @return a cache representing the contents of the specified index file (if
	 *         it exists) or an empty cache if the file does not exist.
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 * @throws IOException
	 *             the index file is present but could not be read, or the lock
	 *             could not be obtained.
	 * @throws CorruptObjectException
	 *             the index file is using a format or extension that this
	 *             library does not support.
	 */
	@NonNull
	public DirCache lockDirCache()
			throws NoWorkTreeException, CorruptObjectException, IOException {
		// we want DirCache to inform us so that we can inform registered
		// listeners about index changes
		IndexChangedListener l = new IndexChangedListener() {

			public void onIndexChanged(IndexChangedEvent event) {
				notifyIndexChanged();
			}
		};
		return DirCache.lock(this, l);
	}

	static byte[] gitInternalSlash(byte[] bytes) {
		if (File.separatorChar == '/')
			return bytes;
		for (int i = 0; i < bytes.length; ++i)
			if (bytes[i] == File.separatorChar)
				bytes[i] = '/';
		return bytes;
	}

	/**
	 * @return an important state
	 */
	@NonNull
	public RepositoryState getRepositoryState() {
		if (isBare() || getDirectory() == null)
			return RepositoryState.BARE;

		// Pre Git-1.6 logic
		if (new File(getWorkTree(), ".dotest").exists()) //$NON-NLS-1$
			return RepositoryState.REBASING;
		if (new File(getDirectory(), ".dotest-merge").exists()) //$NON-NLS-1$
			return RepositoryState.REBASING_INTERACTIVE;

		// From 1.6 onwards
		if (new File(getDirectory(), "rebase-apply/rebasing").exists()) //$NON-NLS-1$
			return RepositoryState.REBASING_REBASING;
		if (new File(getDirectory(), "rebase-apply/applying").exists()) //$NON-NLS-1$
			return RepositoryState.APPLY;
		if (new File(getDirectory(), "rebase-apply").exists()) //$NON-NLS-1$
			return RepositoryState.REBASING;

		if (new File(getDirectory(), "rebase-merge/interactive").exists()) //$NON-NLS-1$
			return RepositoryState.REBASING_INTERACTIVE;
		if (new File(getDirectory(), "rebase-merge").exists()) //$NON-NLS-1$
			return RepositoryState.REBASING_MERGE;

		// Both versions
		if (new File(getDirectory(), Constants.MERGE_HEAD).exists()) {
			// we are merging - now check whether we have unmerged paths
			try {
				if (!readDirCache().hasUnmergedPaths()) {
					// no unmerged paths -> return the MERGING_RESOLVED state
					return RepositoryState.MERGING_RESOLVED;
				}
			} catch (IOException e) {
				// Can't decide whether unmerged paths exists. Return
				// MERGING state to be on the safe side (in state MERGING
				// you are not allow to do anything)
			}
			return RepositoryState.MERGING;
		}

		if (new File(getDirectory(), "BISECT_LOG").exists()) //$NON-NLS-1$
			return RepositoryState.BISECTING;

		if (new File(getDirectory(), Constants.CHERRY_PICK_HEAD).exists()) {
			try {
				if (!readDirCache().hasUnmergedPaths()) {
					// no unmerged paths
					return RepositoryState.CHERRY_PICKING_RESOLVED;
				}
			} catch (IOException e) {
				// fall through to CHERRY_PICKING
			}

			return RepositoryState.CHERRY_PICKING;
		}

		if (new File(getDirectory(), Constants.REVERT_HEAD).exists()) {
			try {
				if (!readDirCache().hasUnmergedPaths()) {
					// no unmerged paths
					return RepositoryState.REVERTING_RESOLVED;
				}
			} catch (IOException e) {
				// fall through to REVERTING
			}

			return RepositoryState.REVERTING;
		}

		return RepositoryState.SAFE;
	}

	/**
	 * Check validity of a ref name. It must not contain character that has a
	 * special meaning in a Git object reference expression. Some other
	 * dangerous characters are also excluded.
	 *
	 * For portability reasons '\' is excluded
	 *
	 * @param refName
	 *
	 * @return true if refName is a valid ref name
	 */
	public static boolean isValidRefName(final String refName) {
		final int len = refName.length();
		if (len == 0)
			return false;
		if (refName.endsWith(".lock")) //$NON-NLS-1$
			return false;

		// Refs may be stored as loose files so invalid paths
		// on the local system must also be invalid refs.
		try {
			SystemReader.getInstance().checkPath(refName);
		} catch (CorruptObjectException e) {
			return false;
		}

		int components = 1;
		char p = '\0';
		for (int i = 0; i < len; i++) {
			final char c = refName.charAt(i);
			if (c <= ' ')
				return false;
			switch (c) {
			case '.':
				switch (p) {
				case '\0':
				case '/':
				case '.':
					return false;
				}
				if (i == len - 1)
					return false;
				break;
			case '/':
				if (i == 0 || i == len - 1)
					return false;
				if (p == '/')
					return false;
				components++;
				break;
			case '{':
				if (p == '@')
					return false;
				break;
			case '~':
			case '^':
			case ':':
			case '?':
			case '[':
			case '*':
			case '\\':
			case '\u007F':
				return false;
			}
			p = c;
		}
		return components > 1;
	}

	/**
	 * Strip work dir and return normalized repository path.
	 *
	 * @param workDir
	 *            Work dir
	 * @param file
	 *            File whose path shall be stripped of its workdir
	 * @return normalized repository relative path or the empty string if the
	 *         file is not relative to the work directory.
	 */
	@NonNull
	public static String stripWorkDir(File workDir, File file) {
		final String filePath = file.getPath();
		final String workDirPath = workDir.getPath();

		if (filePath.length() > workDirPath.length()
				&& filePath.charAt(workDirPath.length()) == File.separatorChar
				&& filePath.startsWith(workDirPath)) {
			String relName = filePath.substring(workDirPath.length() + 1);
			if (File.separatorChar != '/')
				relName = relName.replace(File.separatorChar, '/');
			return relName;
		}
		File absWd = workDir.isAbsolute() ? workDir : workDir.getAbsoluteFile();
		File absFile = file.isAbsolute() ? file : file.getAbsoluteFile();
		if (absWd == workDir && absFile == file)
			return ""; //$NON-NLS-1$
		return stripWorkDir(absWd, absFile);
	}

	/**
	 * @return true if this is bare, which implies it has no working directory.
	 */
	public boolean isBare() {
		return workTree == null;
	}

	/**
	 * @return the root directory of the working tree, where files are checked
	 *         out for viewing and editing.
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 */
	@NonNull
	public File getWorkTree() throws NoWorkTreeException {
		if (isBare())
			throw new NoWorkTreeException();
		return workTree;
	}

	/**
	 * Force a scan for changed refs.
	 *
	 * @throws IOException
	 */
	public abstract void scanForRepoChanges() throws IOException;

	/**
	 * Notify that the index changed
	 */
	public abstract void notifyIndexChanged();

	/**
	 * @param refName
	 *
	 * @return a more user friendly ref name
	 */
	@NonNull
	public static String shortenRefName(String refName) {
		if (refName.startsWith(Constants.R_HEADS))
			return refName.substring(Constants.R_HEADS.length());
		if (refName.startsWith(Constants.R_TAGS))
			return refName.substring(Constants.R_TAGS.length());
		if (refName.startsWith(Constants.R_REMOTES))
			return refName.substring(Constants.R_REMOTES.length());
		return refName;
	}

	/**
	 * @param refName
	 * @return the remote branch name part of <code>refName</code>, i.e. without
	 *         the <code>refs/remotes/&lt;remote&gt;</code> prefix, if
	 *         <code>refName</code> represents a remote tracking branch;
	 *         otherwise {@code null}.
	 * @since 3.4
	 */
	@Nullable
	public String shortenRemoteBranchName(String refName) {
		for (String remote : getRemoteNames()) {
			String remotePrefix = Constants.R_REMOTES + remote + "/"; //$NON-NLS-1$
			if (refName.startsWith(remotePrefix))
				return refName.substring(remotePrefix.length());
		}
		return null;
	}

	/**
	 * @param refName
	 * @return the remote name part of <code>refName</code>, i.e. without the
	 *         <code>refs/remotes/&lt;remote&gt;</code> prefix, if
	 *         <code>refName</code> represents a remote tracking branch;
	 *         otherwise {@code null}.
	 * @since 3.4
	 */
	@Nullable
	public String getRemoteName(String refName) {
		for (String remote : getRemoteNames()) {
			String remotePrefix = Constants.R_REMOTES + remote + "/"; //$NON-NLS-1$
			if (refName.startsWith(remotePrefix))
				return remote;
		}
		return null;
	}

	/**
	 * @param refName
	 * @return a {@link ReflogReader} for the supplied refname, or {@code null}
	 *         if the named ref does not exist.
	 * @throws IOException
	 *             the ref could not be accessed.
	 * @since 3.0
	 */
	@Nullable
	public abstract ReflogReader getReflogReader(String refName)
			throws IOException;

	/**
	 * Return the information stored in the file $GIT_DIR/MERGE_MSG. In this
	 * file operations triggering a merge will store a template for the commit
	 * message of the merge commit.
	 *
	 * @return a String containing the content of the MERGE_MSG file or
	 *         {@code null} if this file doesn't exist
	 * @throws IOException
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 */
	@Nullable
	public String readMergeCommitMsg() throws IOException, NoWorkTreeException {
		return readCommitMsgFile(Constants.MERGE_MSG);
	}

	/**
	 * Write new content to the file $GIT_DIR/MERGE_MSG. In this file operations
	 * triggering a merge will store a template for the commit message of the
	 * merge commit. If <code>null</code> is specified as message the file will
	 * be deleted.
	 *
	 * @param msg
	 *            the message which should be written or <code>null</code> to
	 *            delete the file
	 *
	 * @throws IOException
	 */
	public void writeMergeCommitMsg(String msg) throws IOException {
		File mergeMsgFile = new File(gitDir, Constants.MERGE_MSG);
		writeCommitMsg(mergeMsgFile, msg);
	}

	/**
	 * Return the information stored in the file $GIT_DIR/COMMIT_EDITMSG. In
	 * this file hooks triggered by an operation may read or modify the current
	 * commit message.
	 *
	 * @return a String containing the content of the COMMIT_EDITMSG file or
	 *         {@code null} if this file doesn't exist
	 * @throws IOException
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 * @since 4.0
	 */
	@Nullable
	public String readCommitEditMsg() throws IOException, NoWorkTreeException {
		return readCommitMsgFile(Constants.COMMIT_EDITMSG);
	}

	/**
	 * Write new content to the file $GIT_DIR/COMMIT_EDITMSG. In this file hooks
	 * triggered by an operation may read or modify the current commit message.
	 * If {@code null} is specified as message the file will be deleted.
	 *
	 * @param msg
	 *            the message which should be written or {@code null} to delete
	 *            the file
	 *
	 * @throws IOException
	 * @since 4.0
	 */
	public void writeCommitEditMsg(String msg) throws IOException {
		File commiEditMsgFile = new File(gitDir, Constants.COMMIT_EDITMSG);
		writeCommitMsg(commiEditMsgFile, msg);
	}

	/**
	 * Return the information stored in the file $GIT_DIR/MERGE_HEAD. In this
	 * file operations triggering a merge will store the IDs of all heads which
	 * should be merged together with HEAD.
	 *
	 * @return a list of commits which IDs are listed in the MERGE_HEAD file or
	 *         {@code null} if this file doesn't exist. Also if the file exists
	 *         but is empty {@code null} will be returned
	 * @throws IOException
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 */
	@Nullable
	public List<ObjectId> readMergeHeads()
			throws IOException, NoWorkTreeException {
		if (isBare() || getDirectory() == null)
			throw new NoWorkTreeException();

		byte[] raw = readGitDirectoryFile(Constants.MERGE_HEAD);
		if (raw == null)
			return null;

		LinkedList<ObjectId> heads = new LinkedList<ObjectId>();
		for (int p = 0; p < raw.length;) {
			heads.add(ObjectId.fromString(raw, p));
			p = RawParseUtils.nextLF(raw,
					p + Constants.OBJECT_ID_STRING_LENGTH);
		}
		return heads;
	}

	/**
	 * Write new merge-heads into $GIT_DIR/MERGE_HEAD. In this file operations
	 * triggering a merge will store the IDs of all heads which should be merged
	 * together with HEAD. If <code>null</code> is specified as list of commits
	 * the file will be deleted
	 *
	 * @param heads
	 *            a list of commits which IDs should be written to
	 *            $GIT_DIR/MERGE_HEAD or <code>null</code> to delete the file
	 * @throws IOException
	 */
	public void writeMergeHeads(List<? extends ObjectId> heads)
			throws IOException {
		writeHeadsFile(heads, Constants.MERGE_HEAD);
	}

	/**
	 * Return the information stored in the file $GIT_DIR/CHERRY_PICK_HEAD.
	 *
	 * @return object id from CHERRY_PICK_HEAD file or {@code null} if this file
	 *         doesn't exist. Also if the file exists but is empty {@code null}
	 *         will be returned
	 * @throws IOException
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 */
	@Nullable
	public ObjectId readCherryPickHead()
			throws IOException, NoWorkTreeException {
		if (isBare() || getDirectory() == null)
			throw new NoWorkTreeException();

		byte[] raw = readGitDirectoryFile(Constants.CHERRY_PICK_HEAD);
		if (raw == null)
			return null;

		return ObjectId.fromString(raw, 0);
	}

	/**
	 * Return the information stored in the file $GIT_DIR/REVERT_HEAD.
	 *
	 * @return object id from REVERT_HEAD file or {@code null} if this file
	 *         doesn't exist. Also if the file exists but is empty {@code null}
	 *         will be returned
	 * @throws IOException
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 */
	@Nullable
	public ObjectId readRevertHead() throws IOException, NoWorkTreeException {
		if (isBare() || getDirectory() == null)
			throw new NoWorkTreeException();

		byte[] raw = readGitDirectoryFile(Constants.REVERT_HEAD);
		if (raw == null)
			return null;
		return ObjectId.fromString(raw, 0);
	}

	/**
	 * Write cherry pick commit into $GIT_DIR/CHERRY_PICK_HEAD. This is used in
	 * case of conflicts to store the cherry which was tried to be picked.
	 *
	 * @param head
	 *            an object id of the cherry commit or <code>null</code> to
	 *            delete the file
	 * @throws IOException
	 */
	public void writeCherryPickHead(ObjectId head) throws IOException {
		List<ObjectId> heads = (head != null) ? Collections.singletonList(head)
				: null;
		writeHeadsFile(heads, Constants.CHERRY_PICK_HEAD);
	}

	/**
	 * Write revert commit into $GIT_DIR/REVERT_HEAD. This is used in case of
	 * conflicts to store the revert which was tried to be picked.
	 *
	 * @param head
	 *            an object id of the revert commit or <code>null</code> to
	 *            delete the file
	 * @throws IOException
	 */
	public void writeRevertHead(ObjectId head) throws IOException {
		List<ObjectId> heads = (head != null) ? Collections.singletonList(head)
				: null;
		writeHeadsFile(heads, Constants.REVERT_HEAD);
	}

	/**
	 * Write original HEAD commit into $GIT_DIR/ORIG_HEAD.
	 *
	 * @param head
	 *            an object id of the original HEAD commit or <code>null</code>
	 *            to delete the file
	 * @throws IOException
	 */
	public void writeOrigHead(ObjectId head) throws IOException {
		List<ObjectId> heads = head != null ? Collections.singletonList(head)
				: null;
		writeHeadsFile(heads, Constants.ORIG_HEAD);
	}

	/**
	 * Return the information stored in the file $GIT_DIR/ORIG_HEAD.
	 *
	 * @return object id from ORIG_HEAD file or {@code null} if this file
	 *         doesn't exist. Also if the file exists but is empty {@code null}
	 *         will be returned
	 * @throws IOException
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 */
	@Nullable
	public ObjectId readOrigHead() throws IOException, NoWorkTreeException {
		if (isBare() || getDirectory() == null)
			throw new NoWorkTreeException();

		byte[] raw = readGitDirectoryFile(Constants.ORIG_HEAD);
		return raw != null ? ObjectId.fromString(raw, 0) : null;
	}

	/**
	 * Return the information stored in the file $GIT_DIR/SQUASH_MSG. In this
	 * file operations triggering a squashed merge will store a template for the
	 * commit message of the squash commit.
	 *
	 * @return a String containing the content of the SQUASH_MSG file or
	 *         {@code null} if this file doesn't exist
	 * @throws IOException
	 * @throws NoWorkTreeException
	 *             if this is bare, which implies it has no working directory.
	 *             See {@link #isBare()}.
	 */
	@Nullable
	public String readSquashCommitMsg() throws IOException {
		return readCommitMsgFile(Constants.SQUASH_MSG);
	}

	/**
	 * Write new content to the file $GIT_DIR/SQUASH_MSG. In this file
	 * operations triggering a squashed merge will store a template for the
	 * commit message of the squash commit. If <code>null</code> is specified as
	 * message the file will be deleted.
	 *
	 * @param msg
	 *            the message which should be written or <code>null</code> to
	 *            delete the file
	 *
	 * @throws IOException
	 */
	public void writeSquashCommitMsg(String msg) throws IOException {
		File squashMsgFile = new File(gitDir, Constants.SQUASH_MSG);
		writeCommitMsg(squashMsgFile, msg);
	}

	@Nullable
	private String readCommitMsgFile(String msgFilename) throws IOException {
		if (isBare() || getDirectory() == null)
			throw new NoWorkTreeException();

		File mergeMsgFile = new File(getDirectory(), msgFilename);
		try {
			return RawParseUtils.decode(IO.readFully(mergeMsgFile));
		} catch (FileNotFoundException e) {
			if (mergeMsgFile.exists()) {
				throw e;
			}
			// the file has disappeared in the meantime ignore it
			return null;
		}
	}

	private void writeCommitMsg(File msgFile, String msg) throws IOException {
		if (msg != null) {
			FileOutputStream fos = new FileOutputStream(msgFile);
			try {
				fos.write(msg.getBytes(Constants.CHARACTER_ENCODING));
			} finally {
				fos.close();
			}
		} else {
			FileUtils.delete(msgFile, FileUtils.SKIP_MISSING);
		}
	}

	/**
	 * Read a file from the git directory.
	 *
	 * @param filename
	 * @return the raw contents or {@code null} if the file doesn't exist or is
	 *         empty
	 * @throws IOException
	 */
	@Nullable
	private byte[] readGitDirectoryFile(String filename) throws IOException {
		File file = new File(getDirectory(), filename);
		try {
			byte[] raw = IO.readFully(file);
			return raw.length > 0 ? raw : null;
		} catch (FileNotFoundException notFound) {
			if (file.exists()) {
				throw notFound;
			}
			return null;
		}
	}

	/**
	 * Write the given heads to a file in the git directory.
	 *
	 * @param heads
	 *            a list of object ids to write or null if the file should be
	 *            deleted.
	 * @param filename
	 * @throws FileNotFoundException
	 * @throws IOException
	 */
	private void writeHeadsFile(List<? extends ObjectId> heads, String filename)
			throws FileNotFoundException, IOException {
		File headsFile = new File(getDirectory(), filename);
		if (heads == null) {
			FileUtils.delete(headsFile, FileUtils.SKIP_MISSING);
		} else {
			BufferedOutputStream bos = new SafeBufferedOutputStream(
					new FileOutputStream(headsFile));
			try {
				for (ObjectId id : heads) {
					id.copyTo(bos);
					bos.write('\n');
				}
			} finally {
				bos.close();
			}
		}
	}

	/**
	 * Read a file formatted like the git-rebase-todo file. The "done" file is
	 * also formatted like the git-rebase-todo file. These files can be found in
	 * .git/rebase-merge/ or .git/rebase-append/ folders.
	 *
	 * @param path
	 *            path to the file relative to the repository's git-dir. E.g.
	 *            "rebase-merge/git-rebase-todo" or "rebase-append/done"
	 * @param includeComments
	 *            <code>true</code> if also comments should be reported
	 * @return the list of steps
	 * @throws IOException
	 * @since 3.2
	 */
	@NonNull
	public List<RebaseTodoLine> readRebaseTodo(String path,
			boolean includeComments) throws IOException {
		return new RebaseTodoFile(this).readRebaseTodo(path, includeComments);
	}

	/**
	 * Write a file formatted like a git-rebase-todo file.
	 *
	 * @param path
	 *            path to the file relative to the repository's git-dir. E.g.
	 *            "rebase-merge/git-rebase-todo" or "rebase-append/done"
	 * @param steps
	 *            the steps to be written
	 * @param append
	 *            whether to append to an existing file or to write a new file
	 * @throws IOException
	 * @since 3.2
	 */
	public void writeRebaseTodoFile(String path, List<RebaseTodoLine> steps,
			boolean append) throws IOException {
		new RebaseTodoFile(this).writeRebaseTodoFile(path, steps, append);
	}

	/**
	 * @return the names of all known remotes
	 * @since 3.4
	 */
	@NonNull
	public Set<String> getRemoteNames() {
		return getConfig()
				.getSubsections(ConfigConstants.CONFIG_REMOTE_SECTION);
	}

	/**
	 * Read standard Git environment variables and configure from those.
	 * <p>
	 * This method tries to read the standard Git environment variables, such as
	 * {@code GIT_DIR} and {@code GIT_WORK_TREE} to configure this builder
	 * instance. If an environment variable is set, it overrides the value
	 * already set in this builder.
	 *
	 * @return {@code this} (for chaining calls).
	 */
	public static void readEnvironment(RepositoryBuilder builder) {
		SystemReader sr = SystemReader.getInstance();
		if (builder.getGitDir() == null) {
			String val = sr.getenv(GIT_DIR_KEY);
			if (val != null)
				builder.setGitDir(new File(val));
		}

		if (builder.getObjectDirectory() == null) {
			String val = sr.getenv(GIT_OBJECT_DIRECTORY_KEY);
			if (val != null)
				builder.setObjectDirectory(new File(val));
		}

		if (builder.getAlternateObjectDirectories() == null) {
			String val = sr.getenv(GIT_ALTERNATE_OBJECT_DIRECTORIES_KEY);
			if (val != null) {
				for (String path : val.split(File.pathSeparator))
					builder.addAlternateObjectDirectory(new File(path));
			}
		}

		if (builder.getWorkTree() == null) {
			String val = sr.getenv(GIT_WORK_TREE_KEY);
			if (val != null)
				builder.setWorkTree(new File(val));
		}

		if (builder.getIndexFile() == null) {
			String val = sr.getenv(GIT_INDEX_FILE_KEY);
			if (val != null)
				builder.setIndexFile(new File(val));
		}

		if (builder.ceilingDirectories == null) {
			String val = sr.getenv(GIT_CEILING_DIRECTORIES_KEY);
			if (val != null) {
				for (String path : val.split(File.pathSeparator))
					builder.addCeilingDirectory(new File(path));
			}
		}

	}

	/**
	 * Configure {@code GIT_DIR} by searching up the file system.
	 * <p>
	 * Starts from the supplied directory path and scans up through the parent
	 * directory tree until a Git repository is found. Success can be determined
	 * by checking for {@code getGitDir() != null}.
	 * <p>
	 * The search can be limited to specific spaces of the local filesystem by
	 * {@link #addCeilingDirectory(File)}, or inheriting the list through a
	 * prior call to {@link #readEnvironment()}.
	 *
	 * @param current
	 *            directory to begin searching in.
	 * @return {@code this} (for chaining calls).
	 */
	public static void findGitDir(File current, RepositoryBuilder builder) {
		if (builder.getGitDir() == null) {
			while (current != null) {
				File dir = new File(current, DOT_GIT);
				if (FileKey.isGitRepository(dir)) {
					builder.setGitDir(dir);
					break;
				} else if (dir.isFile()) {
					try {
						builder.setGitDir(getSymRef(current, dir));
						break;
					} catch (IOException ignored) {
						// Continue searching if gitdir ref isn't found
					}
				} else if (FileKey.isGitRepository(current)) {
					builder.setGitDir(current);
					break;
				}

				current = current.getParentFile();
				if (current != null && builder.ceilingDirectories != null
						&& builder.ceilingDirectories.contains(current))
					break;
			}
		}
	}

	/**
	 * Guess and populate all parameters not already defined.
	 * <p>
	 * If an option was not set, the setup method will try to default the option
	 * based on other options. If insufficient information is available, an
	 * exception is thrown to the caller.
	 *
	 * @return {@code this}
	 * @throws IllegalArgumentException
	 *             insufficient parameters were set, or some parameters are
	 *             incompatible with one another.
	 * @throws IOException
	 *             the repository could not be accessed to configure the rest of
	 *             the builder's parameters.
	 */
	public static void setup(RepositoryBuilder builder)
			throws IllegalArgumentException, IOException {
		if (builder.getGitDir() == null && builder.getWorkTree() == null)
			throw new IllegalArgumentException(
					JGitText.get().eitherGitDirOrWorkTreeRequired);
		// No gitDir? Try to assume its under the workTree or a ref to
		// another
		// location
		if (builder.getGitDir() == null && builder.getWorkTree() != null) {
			File dotGit = new File(builder.getWorkTree(), DOT_GIT);
			if (dotGit.isFile())
				builder.setGitDir(getSymRef(builder.getWorkTree(), dotGit));
			else
				builder.setGitDir(dotGit);
		}
		// If we aren't bare, we should have a work tree.
		//
		if (!builder.isBare() && builder.getWorkTree() == null)
			builder.workTree = guessWorkTreeOrFail(builder);

		if (!builder.isBare()) {
			// If after guessing we're still not bare, we must have
			// a metadata directory to hold the repository. Assume
			// its at the work tree.
			//
			if (builder.getGitDir() == null)
				builder.setGitDir(builder.getWorkTree().getParentFile());
			if (builder.getIndexFile() == null)
				builder.setIndexFile(new File(builder.getGitDir(), "index")); //$NON-NLS-1$
		}
		if (builder.getObjectDirectory() == null && builder.getGitDir() != null)
			builder.setObjectDirectory(
					FS.DETECTED.resolve(builder.getGitDir(), "objects")); //$NON-NLS-1$
	}

	/**
	 * Parse and load the repository specific configuration.
	 * <p>
	 * The default implementation reads {@code gitDir/config}, or returns an
	 * empty configuration if gitDir was not set.
	 *
	 * @return the repository's configuration.
	 * @throws IOException
	 *             the configuration is not available.
	 */
	protected static Config loadConfig(RepositoryBuilder builder)
			throws IOException {
		if (builder.getGitDir() == null) {
			return new Config();
		}
		// We only want the repository's configuration file, and not
		// the user file, as these parameters must be unique to this
		// repository and not inherited from other files.
		//
		File path = FS.DETECTED.resolve(builder.getGitDir(), Constants.CONFIG);
		FileBasedConfig cfg = new FileBasedConfig(path);
		try {
			cfg.load();
		} catch (ConfigInvalidException err) {
			throw new IllegalArgumentException(MessageFormat.format(
					JGitText.get().repositoryConfigFileInvalid,
					path.getAbsolutePath(), err.getMessage()));
		}
		return cfg;
	}

	private static File guessWorkTreeOrFail(RepositoryBuilder builder)
			throws IOException {
		if (builder.config == null)
			builder.config = loadConfig(builder);
		final Config cfg = builder.config;

		// If set, core.worktree wins.
		//
		String path = cfg.getString(CONFIG_CORE_SECTION, null,
				CONFIG_KEY_WORKTREE);
		if (path != null)
			return FS.DETECTED.resolve(builder.getGitDir(), path)
					.getCanonicalFile();

		// If core.bare is set, honor its value. Assume workTree is
		// the parent directory of the repository.
		//
		if (cfg.getString(CONFIG_CORE_SECTION, null, CONFIG_KEY_BARE) != null) {
			if (cfg.getBoolean(CONFIG_CORE_SECTION, CONFIG_KEY_BARE, true)) {
				builder.setBare();
				return null;
			}
			return builder.getGitDir().getParentFile();
		}

		if (builder.getGitDir().getName().equals(DOT_GIT)) {
			// No value for the "bare" flag, but gitDir is named ".git",
			// use the parent of the directory
			//
			return builder.getGitDir().getParentFile();
		}

		// We have to assume we are bare.
		//
		builder.setBare();
		return null;
	}

	/**
	 * Base builder to customize repository construction.
	 * <p>
	 * Repository implementations may subclass this builder in order to add
	 * custom repository detection methods.
	 *
	 * @param <B>
	 *            type of the repository builder.
	 * @param <R>
	 *            type of the repository that is constructed.
	 * @see RepositoryBuilder
	 * @see FileRepositoryBuilder
	 */
	public static class RepositoryBuilder {
		private File gitDir;

		private File objectDirectory;

		private List<File> alternateObjectDirectories;

		private File indexFile;

		private File workTree;

		/** Directories limiting the search for a Git repository. */
		private List<File> ceilingDirectories;

		/** True only if the caller wants to force bare behavior. */
		private boolean bare;

		/**
		 * Configuration file of target repository, lazily loaded if required.
		 */
		private Config config;

		/**
		 * Set the Git directory storing the repository metadata.
		 * <p>
		 * The meta directory stores the objects, references, and meta files
		 * like {@code MERGE_HEAD}, or the index file. If {@code null} the path
		 * is assumed to be {@code workTree/.git}.
		 *
		 * @param gitDir
		 *            {@code GIT_DIR}, the repository meta directory.
		 * @return {@code this} (for chaining calls).
		 */
		public void setGitDir(File gitDir) {
			this.gitDir = gitDir;
			this.config = null;
		}

		/** @return the meta data directory; null if not set. */
		public File getGitDir() {
			return gitDir;
		}

		/**
		 * Set the directory storing the repository's objects.
		 *
		 * @param objectDirectory
		 *            {@code GIT_OBJECT_DIRECTORY}, the directory where the
		 *            repository's object files are stored.
		 * @return {@code this} (for chaining calls).
		 */
		public void setObjectDirectory(File objectDirectory) {
			this.objectDirectory = objectDirectory;
		}

		/** @return the object directory; null if not set. */
		public File getObjectDirectory() {
			return objectDirectory;
		}

		/**
		 * Add an alternate object directory to the search list.
		 * <p>
		 * This setting handles one alternate directory at a time, and is
		 * provided to support {@code GIT_ALTERNATE_OBJECT_DIRECTORIES}.
		 *
		 * @param other
		 *            another objects directory to search after the standard
		 *            one.
		 * @return {@code this} (for chaining calls).
		 */
		public void addAlternateObjectDirectory(File other) {
			if (other != null) {
				if (alternateObjectDirectories == null)
					alternateObjectDirectories = new LinkedList<File>();
				alternateObjectDirectories.add(other);
			}
		}

		/**
		 * @return ordered array of alternate directories; null if non were set.
		 */
		public File[] getAlternateObjectDirectories() {
			if (alternateObjectDirectories == null)
				return null;
			return alternateObjectDirectories
					.toArray(new File[alternateObjectDirectories.size()]);
		}

		/**
		 * Force the repository to be treated as bare (have no working
		 * directory).
		 * <p>
		 * If bare the working directory aspects of the repository won't be
		 * configured, and will not be accessible.
		 *
		 * @return {@code this} (for chaining calls).
		 */
		public void setBare() {
			setIndexFile(null);
			setWorkTree(null);
			bare = true;
		}

		/**
		 * @return true if this repository was forced bare by {@link #setBare()}
		 *         .
		 */
		public boolean isBare() {
			return bare;
		}

		/**
		 * Set the top level directory of the working files.
		 *
		 * @param workTree
		 *            {@code GIT_WORK_TREE}, the working directory of the
		 *            checkout.
		 * @return {@code this} (for chaining calls).
		 */
		public void setWorkTree(File workTree) {
			this.workTree = workTree;
		}

		/** @return the work tree directory, or null if not set. */
		public File getWorkTree() {
			return workTree;
		}

		/**
		 * Set the local index file that is caching checked out file status.
		 * <p>
		 * The location of the index file tracking the status information for
		 * each checked out file in {@code workTree}. This may be null to assume
		 * the default {@code gitDiir/index}.
		 *
		 * @param indexFile
		 *            {@code GIT_INDEX_FILE}, the index file location.
		 * @return {@code this} (for chaining calls).
		 */
		public void setIndexFile(File indexFile) {
			this.indexFile = indexFile;
		}

		/** @return the index file location, or null if not set. */
		public File getIndexFile() {
			return indexFile;
		}

		/**
		 * Add a ceiling directory to the search limit list.
		 * <p>
		 * This setting handles one ceiling directory at a time, and is provided
		 * to support {@code GIT_CEILING_DIRECTORIES}.
		 *
		 * @param root
		 *            a path to stop searching at; its parent will not be
		 *            searched.
		 * @return {@code this} (for chaining calls).
		 */
		public void addCeilingDirectory(File root) {
			if (root != null) {
				if (ceilingDirectories == null)
					ceilingDirectories = new LinkedList<File>();
				ceilingDirectories.add(root);
			}
		}

	}
}
