/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.mailbox.store;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.mail.Flags;
import javax.mail.Flags.Flag;
import javax.mail.internet.SharedInputStream;
import javax.mail.util.SharedFileInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.TeeInputStream;
import org.apache.james.mailbox.MailboxListener;
import org.apache.james.mailbox.MailboxPathLocker;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MailboxSession.User;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.acl.GroupMembershipResolver;
import org.apache.james.mailbox.acl.MailboxACLResolver;
import org.apache.james.mailbox.acl.UnionMailboxACLResolver;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.ReadOnlyException;
import org.apache.james.mailbox.exception.UnsupportedRightException;
import org.apache.james.mailbox.model.Attachment;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.MailboxACL;
import org.apache.james.mailbox.model.MailboxACL.MailboxACLRights;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageAttachment;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageId.Factory;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MessageResult.FetchGroup;
import org.apache.james.mailbox.model.MessageResultIterator;
import org.apache.james.mailbox.model.SearchQuery;
import org.apache.james.mailbox.model.SimpleMailboxACL;
import org.apache.james.mailbox.model.UpdatedFlags;
import org.apache.james.mailbox.quota.QuotaManager;
import org.apache.james.mailbox.quota.QuotaRootResolver;
import org.apache.james.mailbox.store.event.MailboxEventDispatcher;
import org.apache.james.mailbox.store.mail.AttachmentMapper;
import org.apache.james.mailbox.store.mail.MessageMapper;
import org.apache.james.mailbox.store.mail.MessageMapper.FetchType;
import org.apache.james.mailbox.store.mail.model.Mailbox;
import org.apache.james.mailbox.store.mail.model.MailboxMessage;
import org.apache.james.mailbox.store.mail.model.impl.MessageParser;
import org.apache.james.mailbox.store.mail.model.impl.PropertyBuilder;
import org.apache.james.mailbox.store.mail.model.impl.SimpleMailboxMessage;
import org.apache.james.mailbox.store.quota.QuotaChecker;
import org.apache.james.mailbox.store.search.MessageSearchIndex;
import org.apache.james.mailbox.store.streaming.BodyOffsetInputStream;
import org.apache.james.mailbox.store.streaming.CountingInputStream;
import org.apache.james.mailbox.store.transaction.Mapper;
import org.apache.james.mime4j.MimeException;
import org.apache.james.mime4j.message.DefaultBodyDescriptorBuilder;
import org.apache.james.mime4j.message.HeaderImpl;
import org.apache.james.mime4j.message.MaximalBodyDescriptor;
import org.apache.james.mime4j.stream.EntityState;
import org.apache.james.mime4j.stream.MimeConfig;
import org.apache.james.mime4j.stream.MimeTokenStream;
import org.apache.james.mime4j.stream.RecursionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;

/**
 * Base class for {@link org.apache.james.mailbox.MessageManager}
 * implementations.
 * 
 * This base class take care of dispatching events to the registered
 * {@link MailboxListener} and so help with handling concurrent
 * {@link MailboxSession}'s.
 * 
 * 
 * 
 */
public class StoreMessageManager implements org.apache.james.mailbox.MessageManager {

    /**
     * The minimal Permanent flags the {@link MessageManager} must support. <br>
     * 
     * <strong>Be sure this static instance will never get modifed
     * later!</strong>
     */
    protected final static Flags MINIMAL_PERMANET_FLAGS;
    static {
        MINIMAL_PERMANET_FLAGS = new Flags();
        MINIMAL_PERMANET_FLAGS.add(Flags.Flag.ANSWERED);
        MINIMAL_PERMANET_FLAGS.add(Flags.Flag.DELETED);
        MINIMAL_PERMANET_FLAGS.add(Flags.Flag.DRAFT);
        MINIMAL_PERMANET_FLAGS.add(Flags.Flag.FLAGGED);
        MINIMAL_PERMANET_FLAGS.add(Flags.Flag.SEEN);
    }

    private static final Logger LOG = LoggerFactory.getLogger(StoreMessageManager.class);


    private final Mailbox mailbox;

    private final MailboxEventDispatcher dispatcher;

    private final MailboxSessionMapperFactory mapperFactory;

    private final MessageSearchIndex index;

    private final MailboxACLResolver aclResolver;

    private final GroupMembershipResolver groupMembershipResolver;

    private final QuotaManager quotaManager;

    private final QuotaRootResolver quotaRootResolver;

    private final MailboxPathLocker locker;

    private final MessageParser messageParser;

    private final Factory messageIdFactory;
    
    private int fetchBatchSize;

    public StoreMessageManager(MailboxSessionMapperFactory mapperFactory, MessageSearchIndex index, MailboxEventDispatcher dispatcher, 
            MailboxPathLocker locker, Mailbox mailbox, MailboxACLResolver aclResolver, GroupMembershipResolver groupMembershipResolver,
            QuotaManager quotaManager, QuotaRootResolver quotaRootResolver, MessageParser messageParser, MessageId.Factory messageIdFactory) throws MailboxException {
        this.mailbox = mailbox;
        this.dispatcher = dispatcher;
        this.mapperFactory = mapperFactory;
        this.index = index;
        this.locker = locker;
        this.aclResolver = aclResolver;
        this.groupMembershipResolver = groupMembershipResolver;
        this.quotaManager = quotaManager;
        this.quotaRootResolver = quotaRootResolver;
        this.messageParser = messageParser;
        this.messageIdFactory = messageIdFactory;
    }

    public void setFetchBatchSize(int fetchBatchSize) {
        this.fetchBatchSize = fetchBatchSize;
    }

    protected Factory getMessageIdFactory() {
        return messageIdFactory;
    }
    
    /**
     * Return the {@link MailboxPathLocker}
     * 
     * @return locker
     */
    protected MailboxPathLocker getLocker() {
        return locker;
    }

    /**
     * Return the {@link MailboxEventDispatcher} for this Mailbox
     * 
     * @return dispatcher
     */
    protected MailboxEventDispatcher getDispatcher() {
        return dispatcher;
    }

    /**
     * Return the underlying {@link Mailbox}
     * 
     * @return mailbox
     * @throws MailboxException
     */

    public Mailbox getMailboxEntity() throws MailboxException {
        return mailbox;
    }

    /**
     * Return {@link Flags} which are permanent stored by the mailbox. By
     * default this are the following flags: <br>
     * {@link Flag#ANSWERED}, {@link Flag#DELETED}, {@link Flag#DRAFT},
     * {@link Flag#FLAGGED}, {@link Flag#RECENT}, {@link Flag#SEEN} <br>
     * 
     * Which in fact does not allow to permanent store user flags / keywords.
     * 
     * If the sub-class does allow to store "any" user flag / keyword it MUST
     * override this method and add {@link Flag#USER} to the list of returned
     * {@link Flags}. If only a special set of user flags / keywords should be
     * allowed just add them directly.
     * 
     * @param session
     * @return flags
     */
    protected Flags getPermanentFlags(MailboxSession session) {

        // Return a new flags instance to make sure the static declared flags
        // instance will not get modified later.
        //
        // See MAILBOX-109
        return new Flags(MINIMAL_PERMANET_FLAGS);
    }

    @Override
    public long getUnseenMessageCount(MailboxSession mailboxSession) throws MailboxException {
        return mapperFactory.createMessageMapper(mailboxSession)
            .countUnseenMessagesInMailbox(mailbox);
    }

    /**
     * Returns the flags which are shared for the current mailbox, i.e. the
     * flags set up so that changes to those flags are visible to another user.
     * See RFC 4314 section 5.2.
     * 
     * In this implementation, all permanent flags are shared, ergo we simply
     * return {@link #getPermanentFlags(MailboxSession)}
     * 
     * @see UnionMailboxACLResolver#isReadWrite(MailboxACLRights, Flags)
     * 
     * @param session
     * @return
     */
    protected Flags getSharedPermanentFlags(MailboxSession session) {
        return getPermanentFlags(session);
    }

    /**
     * Return true. If an subclass don't want to store mod-sequences in a
     * permanent way just override this and return false
     * 
     * @return true
     */
    public boolean isModSeqPermanent(MailboxSession session) {
        return true;
    }

    @Override
    public Iterator<MessageUid> expunge(MessageRange set, MailboxSession mailboxSession) throws MailboxException {
        if (!isWriteable(mailboxSession)) {
            throw new ReadOnlyException(getMailboxPath(), mailboxSession.getPathDelimiter());
        }
        Map<MessageUid, MessageMetaData> uids = deleteMarkedInMailbox(set, mailboxSession);

        dispatcher.expunged(mailboxSession, uids, getMailboxEntity());
        return uids.keySet().iterator();
    }

    @Override
    public ComposedMessageId appendMessage(InputStream msgIn, Date internalDate, final MailboxSession mailboxSession, boolean isRecent, Flags flagsToBeSet) throws MailboxException {

        File file = null;
        TeeInputStream tmpMsgIn = null;
        BodyOffsetInputStream bIn = null;
        FileOutputStream out = null;
        SharedFileInputStream contentIn = null;

        if (!isWriteable(mailboxSession)) {
            throw new ReadOnlyException(getMailboxPath(), mailboxSession.getPathDelimiter());
        }

        try {
            // Create a temporary file and copy the message to it. We will work
            // with the file as
            // source for the InputStream
            file = File.createTempFile("imap", ".msg");
            out = new FileOutputStream(file);

            tmpMsgIn = new TeeInputStream(msgIn, out);

            bIn = new BodyOffsetInputStream(tmpMsgIn);
            // Disable line length... This should be handled by the smtp server
            // component and not the parser itself
            // https://issues.apache.org/jira/browse/IMAP-122
            MimeConfig config = MimeConfig.custom().setMaxLineLen(-1).setMaxHeaderLen(-1).build();

            final MimeTokenStream parser = new MimeTokenStream(config, new DefaultBodyDescriptorBuilder());

            parser.setRecursionMode(RecursionMode.M_NO_RECURSE);
            parser.parse(bIn);
            final HeaderImpl header = new HeaderImpl();

            EntityState next = parser.next();
            while (next != EntityState.T_BODY && next != EntityState.T_END_OF_STREAM && next != EntityState.T_START_MULTIPART) {
                if (next == EntityState.T_FIELD) {
                    header.addField(parser.getField());
                }
                next = parser.next();
            }
            final MaximalBodyDescriptor descriptor = (MaximalBodyDescriptor) parser.getBodyDescriptor();
            final PropertyBuilder propertyBuilder = new PropertyBuilder();
            final String mediaType;
            final String mediaTypeFromHeader = descriptor.getMediaType();
            final String subType;
            if (mediaTypeFromHeader == null) {
                mediaType = "text";
                subType = "plain";
            } else {
                mediaType = mediaTypeFromHeader;
                subType = descriptor.getSubType();
            }
            propertyBuilder.setMediaType(mediaType);
            propertyBuilder.setSubType(subType);
            propertyBuilder.setContentID(descriptor.getContentId());
            propertyBuilder.setContentDescription(descriptor.getContentDescription());
            propertyBuilder.setContentLocation(descriptor.getContentLocation());
            propertyBuilder.setContentMD5(descriptor.getContentMD5Raw());
            propertyBuilder.setContentTransferEncoding(descriptor.getTransferEncoding());
            propertyBuilder.setContentLanguage(descriptor.getContentLanguage());
            propertyBuilder.setContentDispositionType(descriptor.getContentDispositionType());
            propertyBuilder.setContentDispositionParameters(descriptor.getContentDispositionParameters());
            propertyBuilder.setContentTypeParameters(descriptor.getContentTypeParameters());
            // Add missing types
            final String codeset = descriptor.getCharset();
            if (codeset == null) {
                if ("TEXT".equalsIgnoreCase(mediaType)) {
                    propertyBuilder.setCharset("us-ascii");
                }
            } else {
                propertyBuilder.setCharset(codeset);
            }

            final String boundary = descriptor.getBoundary();
            if (boundary != null) {
                propertyBuilder.setBoundary(boundary);
            }
            if ("text".equalsIgnoreCase(mediaType)) {
                final CountingInputStream bodyStream = new CountingInputStream(parser.getInputStream());
                bodyStream.readAll();
                long lines = bodyStream.getLineCount();
                bodyStream.close();
                next = parser.next();
                if (next == EntityState.T_EPILOGUE) {
                    final CountingInputStream epilogueStream = new CountingInputStream(parser.getInputStream());
                    epilogueStream.readAll();
                    lines += epilogueStream.getLineCount();
                    epilogueStream.close();

                }
                propertyBuilder.setTextualLineCount(lines);
            }

            final Flags flags;
            if (flagsToBeSet == null) {
                flags = new Flags();
            } else {
                flags = flagsToBeSet;

                // Check if we need to trim the flags
                trimFlags(flags, mailboxSession);

            }
            if (isRecent) {
                flags.add(Flags.Flag.RECENT);
            }
            if (internalDate == null) {
                internalDate = new Date();
            }
            byte[] discard = new byte[4096];
            while (tmpMsgIn.read(discard) != -1) {
                // consume the rest of the stream so everything get copied to
                // the file now
                // via the TeeInputStream
            }
            int bodyStartOctet = (int) bIn.getBodyStartOffset();
            if (bodyStartOctet == -1) {
                bodyStartOctet = 0;
            }
            contentIn = new SharedFileInputStream(file);
            final int size = (int) file.length();

            final List<MessageAttachment> attachments = extractAttachments(contentIn);
            final MailboxMessage message = createMessage(internalDate, size, bodyStartOctet, contentIn, flags, propertyBuilder, attachments);

            new QuotaChecker(quotaManager, quotaRootResolver, mailbox).tryAddition(1, size);

            return locker.executeWithLock(mailboxSession, getMailboxPath(), new MailboxPathLocker.LockAwareExecution<ComposedMessageId>() {

                @Override
                public ComposedMessageId execute() throws MailboxException {
                    MessageMetaData data = appendMessageToStore(message, attachments, mailboxSession);

                    SortedMap<MessageUid, MessageMetaData> uids = new TreeMap<MessageUid, MessageMetaData>();
                    MessageUid messageUid = data.getUid();
                    MailboxId mailboxId = getMailboxEntity().getMailboxId();
                    uids.put(messageUid, data);
                    dispatcher.added(mailboxSession, uids, getMailboxEntity());
                    return new ComposedMessageId(mailboxId, data.getMessageId(), messageUid);
                }
            }, true);

        } catch (IOException e) {
            throw new MailboxException("Unable to parse message", e);
        } catch (MimeException e) {
            throw new MailboxException("Unable to parse message", e);
        } finally {
            IOUtils.closeQuietly(bIn);
            IOUtils.closeQuietly(tmpMsgIn);
            IOUtils.closeQuietly(out);
            IOUtils.closeQuietly(contentIn);

            // delete the temporary file if one was specified
            if (file != null) {
                if (!file.delete()) {
                    // Don't throw an IOException. The message could be appended
                    // and the temporary file
                    // will be deleted hopefully some day
                }
            }
        }

    }

    private List<MessageAttachment> extractAttachments(SharedFileInputStream contentIn) {
        try {
            return messageParser.retrieveAttachments(contentIn);
        } catch (Exception e) {
            LOG.warn("Error while parsing mail's attachments: " + e.getMessage(), e);
            return ImmutableList.of();
        }
    }

    /**
     * Create a new {@link MailboxMessage} for the given data
     */
    protected MailboxMessage createMessage(Date internalDate, int size, int bodyStartOctet, SharedInputStream content, Flags flags, PropertyBuilder propertyBuilder, List<MessageAttachment> attachments) throws MailboxException {
        return new SimpleMailboxMessage(messageIdFactory.generate(), internalDate, size, bodyStartOctet, content, flags, propertyBuilder, getMailboxEntity().getMailboxId(), attachments);
    }

    /**
     * This mailbox is writable
     * 
     * @throws MailboxException
     */
    public boolean isWriteable(MailboxSession session) throws MailboxException {
        return aclResolver.isReadWrite(myRights(session), getSharedPermanentFlags(session));
    }

    /**
     * @see MessageManager#getMetaData(boolean, MailboxSession,
     *      org.apache.james.mailbox.MessageManager.MetaData.FetchGroup)
     */
    public MetaData getMetaData(boolean resetRecent, MailboxSession mailboxSession, org.apache.james.mailbox.MessageManager.MetaData.FetchGroup fetchGroup) throws MailboxException {

        final List<MessageUid> recent;
        final Flags permanentFlags = getPermanentFlags(mailboxSession);
        final long uidValidity = getMailboxEntity().getUidValidity();
        MessageUid uidNext = mapperFactory.getMessageMapper(mailboxSession).getLastUid(mailbox)
                .transform(new Function<MessageUid, MessageUid>() {
                    public MessageUid apply(MessageUid input) {
                        return input.next();
                    }})
                .or(MessageUid.MIN_VALUE);
        final long highestModSeq = mapperFactory.getMessageMapper(mailboxSession).getHighestModSeq(mailbox);
        final long messageCount;
        final long unseenCount;
        final MessageUid firstUnseen;
        switch (fetchGroup) {
        case UNSEEN_COUNT:
            unseenCount = countUnseenMessagesInMailbox(mailboxSession);
            messageCount = getMessageCount(mailboxSession);
            firstUnseen = null;
            recent = recent(resetRecent, mailboxSession);

            break;
        case FIRST_UNSEEN:
            firstUnseen = findFirstUnseenMessageUid(mailboxSession);
            messageCount = getMessageCount(mailboxSession);
            unseenCount = 0;
            recent = recent(resetRecent, mailboxSession);

            break;
        case NO_UNSEEN:
            firstUnseen = null;
            unseenCount = 0;
            messageCount = getMessageCount(mailboxSession);
            recent = recent(resetRecent, mailboxSession);

            break;
        default:
            firstUnseen = null;
            unseenCount = 0;
            messageCount = -1;
            // just reset the recent but not include them in the metadata
            if (resetRecent) {
                recent(resetRecent, mailboxSession);
            }
            recent = new ArrayList<MessageUid>();
            break;
        }
        MailboxACL resolvedAcl = getResolvedMailboxACL(mailboxSession);
        return new MailboxMetaData(recent, permanentFlags, uidValidity, uidNext, highestModSeq, messageCount, unseenCount, firstUnseen, isWriteable(mailboxSession), isModSeqPermanent(mailboxSession), resolvedAcl);
    }

    /**
     * Check if the given {@link Flags} contains {@link Flags} which are not
     * included in the returned {@link Flags} of
     * {@link #getPermanentFlags(MailboxSession)}. If any are found, these are
     * removed from the given {@link Flags} instance. The only exception is the
     * {@link Flag#RECENT} flag.
     * 
     * This flag is never removed!
     * 
     * @param flags
     * @param session
     */
    private void trimFlags(Flags flags, MailboxSession session) {

        Flags permFlags = getPermanentFlags(session);

        Flag[] systemFlags = flags.getSystemFlags();
        for (Flag f : systemFlags) {
            if (f != Flag.RECENT && permFlags.contains(f) == false) {
                flags.remove(f);
            }
        }
        // if the permFlags contains the special USER flag we can skip this as
        // all user flags are allowed
        if (permFlags.contains(Flags.Flag.USER) == false) {
            String[] uFlags = flags.getUserFlags();
            for (String uFlag : uFlags) {
                if (permFlags.contains(uFlag) == false) {
                    flags.remove(uFlag);
                }
            }
        }

    }

    /**
     * @see org.apache.james.mailbox.MessageManager#setFlags(javax.mail.Flags,
     *      boolean, boolean, org.apache.james.mailbox.model.MessageRange,
     *      org.apache.james.mailbox.MailboxSession)
     */
    public Map<MessageUid, Flags> setFlags(final Flags flags, final FlagsUpdateMode flagsUpdateMode, final MessageRange set, MailboxSession mailboxSession) throws MailboxException {

        if (!isWriteable(mailboxSession)) {
            throw new ReadOnlyException(getMailboxPath(), mailboxSession.getPathDelimiter());
        }
        final SortedMap<MessageUid, Flags> newFlagsByUid = new TreeMap<MessageUid, Flags>();

        trimFlags(flags, mailboxSession);

        final MessageMapper messageMapper = mapperFactory.getMessageMapper(mailboxSession);

        Iterator<UpdatedFlags> it = messageMapper.execute(new Mapper.Transaction<Iterator<UpdatedFlags>>() {

            public Iterator<UpdatedFlags> run() throws MailboxException {
                return messageMapper.updateFlags(getMailboxEntity(), new FlagsUpdateCalculator(flags, flagsUpdateMode), set);
            }
        });

        final SortedMap<MessageUid, UpdatedFlags> uFlags = new TreeMap<MessageUid, UpdatedFlags>();

        while (it.hasNext()) {
            UpdatedFlags flag = it.next();
            newFlagsByUid.put(flag.getUid(), flag.getNewFlags());
            uFlags.put(flag.getUid(), flag);
        }

        dispatcher.flagsUpdated(mailboxSession, new ArrayList<MessageUid>(uFlags.keySet()), getMailboxEntity(), new ArrayList<UpdatedFlags>(uFlags.values()));

        return newFlagsByUid;
    }

    /**
     * Copy the {@link MessageRange} to the {@link StoreMessageManager}
     * 
     * @param set
     * @param toMailbox
     * @param session
     * @throws MailboxException
     */
    public List<MessageRange> copyTo(final MessageRange set, final StoreMessageManager toMailbox, final MailboxSession session) throws MailboxException {
        if (!toMailbox.isWriteable(session)) {
            throw new ReadOnlyException(new StoreMailboxPath(toMailbox.getMailboxEntity()), session.getPathDelimiter());
        }

        return locker.executeWithLock(session, new StoreMailboxPath(toMailbox.getMailboxEntity()), new MailboxPathLocker.LockAwareExecution<List<MessageRange>>() {

            @Override
            public List<MessageRange> execute() throws MailboxException {
                SortedMap<MessageUid, MessageMetaData> copiedUids = copy(set, toMailbox, session);
                dispatcher.added(session, copiedUids, toMailbox.getMailboxEntity());
                return MessageRange.toRanges(new ArrayList<MessageUid>(copiedUids.keySet()));
            }
        }, true);
    }

    /**
     * Move the {@link MessageRange} to the {@link StoreMessageManager}
     * 
     * @param set
     * @param toMailbox
     * @param session
     * @throws MailboxException
     */
    public List<MessageRange> moveTo(final MessageRange set, final StoreMessageManager toMailbox, final MailboxSession session) throws MailboxException {
        if (!isWriteable(session)) {
            throw new ReadOnlyException(getMailboxPath(), session.getPathDelimiter());
        }
        if (!toMailbox.isWriteable(session)) {
            throw new ReadOnlyException(new StoreMailboxPath(toMailbox.getMailboxEntity()), session.getPathDelimiter());
        }

        //TODO lock the from mailbox too, in a non-deadlocking manner - how?
        return locker.executeWithLock(session, new StoreMailboxPath(toMailbox.getMailboxEntity()), new MailboxPathLocker.LockAwareExecution<List<MessageRange>>() {

            @Override
            public List<MessageRange> execute() throws MailboxException {
                SortedMap<MessageUid, MessageMetaData> movedUids = move(set, toMailbox, session);
                dispatcher.added(session, movedUids, toMailbox.getMailboxEntity());
                return MessageRange.toRanges(new ArrayList<MessageUid>(movedUids.keySet()));
            }
        }, true);
    }

    protected MessageMetaData appendMessageToStore(final MailboxMessage message, final List<MessageAttachment> messageAttachments, MailboxSession session) throws MailboxException {
        final MessageMapper messageMapper = mapperFactory.getMessageMapper(session);
        final AttachmentMapper attachmentMapper = mapperFactory.getAttachmentMapper(session);
        return mapperFactory.getMessageMapper(session).execute(new Mapper.Transaction<MessageMetaData>() {

            public MessageMetaData run() throws MailboxException {
                ImmutableList.Builder<Attachment> attachments = ImmutableList.builder();
                for (MessageAttachment attachment : messageAttachments) {
                    attachments.add(attachment.getAttachment());
                }
                attachmentMapper.storeAttachments(attachments.build());
                return messageMapper.add(getMailboxEntity(), message);
            }

        });
    }

    /**
     * @see org.apache.james.mailbox.MessageManager#getMessageCount(org.apache.james.mailbox.MailboxSession)
     */
    public long getMessageCount(MailboxSession mailboxSession) throws MailboxException {
        return mapperFactory.getMessageMapper(mailboxSession).countMessagesInMailbox(getMailboxEntity());
    }

    /**
     * @see org.apache.james.mailbox.MessageManager#getMessages(org.apache.james.mailbox.model.MessageRange,
     *      org.apache.james.mailbox.model.MessageResult.FetchGroup,
     *      org.apache.james.mailbox.MailboxSession)
     */
    public MessageResultIterator getMessages(MessageRange set, FetchGroup fetchGroup, MailboxSession mailboxSession) throws MailboxException {
        final MessageMapper messageMapper = mapperFactory.getMessageMapper(mailboxSession);
        return new StoreMessageResultIterator(messageMapper, mailbox, set, fetchBatchSize, fetchGroup);
    }

    /**
     * Return a List which holds all uids of recent messages and optional reset
     * the recent flag on the messages for the uids
     */
    protected List<MessageUid> recent(final boolean reset, MailboxSession mailboxSession) throws MailboxException {
        if (reset) {
            if (!isWriteable(mailboxSession)) {
                throw new ReadOnlyException(getMailboxPath(), mailboxSession.getPathDelimiter());
            }
        }
        final MessageMapper messageMapper = mapperFactory.getMessageMapper(mailboxSession);

        return messageMapper.execute(new Mapper.Transaction<List<MessageUid>>() {

            public List<MessageUid> run() throws MailboxException {
                final List<MessageUid> members = messageMapper.findRecentMessageUidsInMailbox(getMailboxEntity());

                // Convert to MessageRanges so we may be able to optimize the
                // flag update
                List<MessageRange> ranges = MessageRange.toRanges(members);
                for (MessageRange range : ranges) {
                    if (reset) {
                        // only call save if we need to
                        messageMapper.updateFlags(getMailboxEntity(), new FlagsUpdateCalculator(new Flags(Flag.RECENT), FlagsUpdateMode.REMOVE), range);
                    }
                }
                return members;
            }

        });

    }

    protected Map<MessageUid, MessageMetaData> deleteMarkedInMailbox(final MessageRange range, MailboxSession session) throws MailboxException {

        final MessageMapper messageMapper = mapperFactory.getMessageMapper(session);

        return messageMapper.execute(new Mapper.Transaction<Map<MessageUid, MessageMetaData>>() {

            public Map<MessageUid, MessageMetaData> run() throws MailboxException {
                return messageMapper.expungeMarkedForDeletionInMailbox(getMailboxEntity(), range);
            }

        });
    }

    @Override
    public Iterator<MessageUid> search(SearchQuery query, MailboxSession mailboxSession) throws MailboxException {
        return index.search(mailboxSession, getMailboxEntity(), query);
    }

    private Iterator<MessageMetaData> copy(Iterator<MailboxMessage> originalRows, MailboxSession session) throws MailboxException {
        final List<MessageMetaData> copiedRows = new ArrayList<MessageMetaData>();
        final MessageMapper messageMapper = mapperFactory.getMessageMapper(session);
        QuotaChecker quotaChecker = new QuotaChecker(quotaManager, quotaRootResolver, mailbox);

        while (originalRows.hasNext()) {
            final MailboxMessage originalMessage = originalRows.next();
            quotaChecker.tryAddition(1, originalMessage.getFullContentOctets());
            MessageMetaData data = messageMapper.execute(new Mapper.Transaction<MessageMetaData>() {
                public MessageMetaData run() throws MailboxException {
                    return messageMapper.copy(getMailboxEntity(), originalMessage);

                }

            });
            copiedRows.add(data);
        }
        return copiedRows.iterator();
    }

    private MoveResult move(Iterator<MailboxMessage> originalRows, MailboxSession session) throws MailboxException {
        final List<MessageMetaData> movedRows = new ArrayList<MessageMetaData>();
        final List<MessageMetaData> originalRowsCopy = new ArrayList<MessageMetaData>();
        final MessageMapper messageMapper = mapperFactory.getMessageMapper(session);

        while (originalRows.hasNext()) {
            final MailboxMessage originalMessage = originalRows.next();
            originalRowsCopy.add(new SimpleMessageMetaData(originalMessage));
            MessageMetaData data = messageMapper.execute(new Mapper.Transaction<MessageMetaData>() {
                public MessageMetaData run() throws MailboxException {
                    return messageMapper.move(getMailboxEntity(), originalMessage);
                }

            });
            movedRows.add(data);
        }
        return new MoveResult(movedRows.iterator(), originalRowsCopy.iterator());
	}


    private SortedMap<MessageUid, MessageMetaData> copy(MessageRange set, StoreMessageManager to, MailboxSession session) throws MailboxException {
        Iterator<MailboxMessage> originalRows = retrieveOriginalRows(set, session);
        return collectMetadata(to.copy(originalRows, session));
    }

    private SortedMap<MessageUid, MessageMetaData> move(MessageRange set, StoreMessageManager to, MailboxSession session) throws MailboxException {
        Iterator<MailboxMessage> originalRows = retrieveOriginalRows(set, session);
        MoveResult moveResult = to.move(originalRows, session);
        dispatcher.expunged(session, collectMetadata(moveResult.getOriginalMessages()), getMailboxEntity());
        return collectMetadata(moveResult.getMovedMessages());
    }

    private Iterator<MailboxMessage> retrieveOriginalRows(MessageRange set, MailboxSession session) throws MailboxException {
        MessageMapper messageMapper = mapperFactory.getMessageMapper(session);
        return messageMapper.findInMailbox(mailbox, set, FetchType.Full, -1);
    }

    private SortedMap<MessageUid, MessageMetaData> collectMetadata(Iterator<MessageMetaData> ids) {
        final SortedMap<MessageUid, MessageMetaData> copiedMessages = new TreeMap<MessageUid, MessageMetaData>();
        while (ids.hasNext()) {
            MessageMetaData data = ids.next();
            copiedMessages.put(data.getUid(), data);
        }
        return copiedMessages;
    }
    /**
     * Return the count of unseen messages
     * 
     * @param session
     * @return count of unseen messages
     * @throws MailboxException
     */
    protected long countUnseenMessagesInMailbox(MailboxSession session) throws MailboxException {
        MessageMapper messageMapper = mapperFactory.getMessageMapper(session);
        return messageMapper.countUnseenMessagesInMailbox(getMailboxEntity());
    }

    /**
     * Return the uid of the first unseen message or null of none is found
     */
    protected MessageUid findFirstUnseenMessageUid(MailboxSession session) throws MailboxException {
        MessageMapper messageMapper = mapperFactory.getMessageMapper(session);
        return messageMapper.findFirstUnseenMessageUid(getMailboxEntity());
    }

    private MailboxACLRights myRights(MailboxSession session) throws MailboxException {
        User user = session.getUser();
        if (user != null) {
            return aclResolver.resolveRights(user.getUserName(), groupMembershipResolver, mailbox.getACL(), mailbox.getUser(), new GroupFolderResolver(session).isGroupFolder(mailbox));
        } else {
            return SimpleMailboxACL.NO_RIGHTS;
        }
    }

    /**
     * Applies the global ACL (if there are any) to the mailbox ACL.
     * 
     * @param mailboxSession
     * @return the ACL of the present mailbox merged with the global ACL (if
     *         there are any).
     * @throws UnsupportedRightException
     */
    protected MailboxACL getResolvedMailboxACL(MailboxSession mailboxSession) throws UnsupportedRightException {
        return aclResolver.applyGlobalACL(mailbox.getACL(), new GroupFolderResolver(mailboxSession).isGroupFolder(mailbox));
    }
    
    @Override
    public MailboxId getId() {
        return mailbox.getMailboxId();
    }
    
    @Override
    public MailboxPath getMailboxPath() throws MailboxException {
        return new StoreMailboxPath(getMailboxEntity());
    }
}
