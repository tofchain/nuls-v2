/**
 * MIT License
 * <p>
 * Copyright (c) 2017-2018 nuls.io
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.nuls.contract.service.impl;

import io.nuls.base.basic.AddressTool;
import io.nuls.base.data.BlockHeader;
import io.nuls.contract.callable.ContractBatchEndCallable;
import io.nuls.contract.callable.ContractTxCallable;
import io.nuls.contract.helper.ContractConflictChecker;
import io.nuls.contract.helper.ContractHelper;
import io.nuls.contract.helper.ContractTransferHandler;
import io.nuls.contract.manager.ContractTempBalanceManager;
import io.nuls.contract.model.bo.BatchInfo;
import io.nuls.contract.model.bo.ContractContainer;
import io.nuls.contract.model.bo.ContractResult;
import io.nuls.contract.model.bo.ContractWrapperTransaction;
import io.nuls.contract.model.dto.ContractPackageDto;
import io.nuls.contract.model.txdata.ContractData;
import io.nuls.contract.rpc.call.BlockCall;
import io.nuls.contract.service.ContractCaller;
import io.nuls.contract.service.ContractExecutor;
import io.nuls.contract.util.Log;
import io.nuls.contract.vm.program.ProgramExecutor;
import io.nuls.tools.basic.Result;
import io.nuls.tools.core.annotation.Autowired;
import io.nuls.tools.core.annotation.Component;
import io.nuls.tools.thread.commom.NulsThreadFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static io.nuls.contract.constant.ContractConstant.TX_TYPE_CALL_CONTRACT;
import static io.nuls.contract.util.ContractUtil.*;


/**
 * @author: PierreLuo
 * @date: 2018/11/19
 */
@Component
public class ContractCallerImpl implements ContractCaller {

    private static final ExecutorService TX_EXECUTOR_SERVICE =
            new ThreadPoolExecutor(
                    Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().availableProcessors() * 2,
                    10L,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<Runnable>(),
                    new NulsThreadFactory("contract-tx-executor-pool"));
    private static final ExecutorService BATCH_END_SERVICE = Executors.newSingleThreadExecutor(new NulsThreadFactory("contract-batch-end-pool"));

    @Autowired
    private ContractExecutor contractExecutor;

    @Autowired
    private ContractHelper contractHelper;

    @Autowired
    private ContractTransferHandler contractTransferHandler;

    @Override
    public Result callTx(int chainId, ContractContainer container, ProgramExecutor batchExecutor, ContractWrapperTransaction tx, String preStateRoot) {

        try {

            ContractData contractData = tx.getContractData();
            byte[] contractAddressBytes = contractData.getContractAddress();
            String contract = AddressTool.getStringAddressByBytes(contractAddressBytes);
            BatchInfo batchInfo = contractHelper.getChain(chainId).getBatchInfo();
            ContractConflictChecker checker = batchInfo.getChecker();
            BlockHeader currentBlockHeader = batchInfo.getCurrentBlockHeader();
            long blockTime = currentBlockHeader.getTime();
            long lastestHeight = currentBlockHeader.getHeight() - 1;
            BlockHeader latestBlockHeader = BlockCall.getLatestBlockHeader(chainId);
            Log.debug("Current block header height is {}", currentBlockHeader.getHeight());
            Log.debug("Latest block header height is {}", latestBlockHeader.getHeight());
            ContractTxCallable txCallable = new ContractTxCallable(chainId, blockTime, batchExecutor, contract, tx, lastestHeight, preStateRoot, checker, container);

            Future<ContractResult> contractResultFuture = TX_EXECUTOR_SERVICE.submit(txCallable);
            container.getFutureList().add(contractResultFuture);

            return getSuccess();
        } catch (Exception e) {
            Log.error(e);
            return getFailed();
        }
    }

    @Override
    public Result callBatchEnd(int chainId, long blockHeight) {
        try {
            Log.info("[Before End] contract batch, blockHeight is {}", blockHeight);
            BatchInfo batchInfo = contractHelper.getChain(chainId).getBatchInfo();
            ContractBatchEndCallable callable = new ContractBatchEndCallable(chainId, blockHeight);
            Future<ContractPackageDto> contractPackageDtoFuture = BATCH_END_SERVICE.submit(callable);
            batchInfo.setContractPackageDtoFuture(contractPackageDtoFuture);
            return getSuccess();
        } catch (Exception e) {
            Log.error(e);
            return getFailed();
        }
    }

    @Override
    public List<ContractResult> reCallTx(ProgramExecutor batchExecutor, List<ContractWrapperTransaction> reCallTxList, int chainId, String preStateRoot) {
        BlockHeader currentBlockHeader = contractHelper.getBatchInfoCurrentBlockHeader(chainId);
        long blockTime = currentBlockHeader.getTime();
        long lastestHeight = currentBlockHeader.getHeight() - 1;
        ContractTempBalanceManager tempBalanceManager = contractHelper.getBatchInfoTempBalanceManager(chainId);
        List<ContractResult> resultList = new ArrayList<>();
        ContractData contractData;
        ContractResult contractResult;
        for (ContractWrapperTransaction tx : reCallTxList) {
            contractData = tx.getContractData();
            switch (tx.getType()) {
                case TX_TYPE_CALL_CONTRACT:
                    contractResult = contractExecutor.call(batchExecutor, contractData, lastestHeight, preStateRoot);
                    makeContractResult(tx, contractResult);
                    // 处理重新执行的合约的结果
                    contractTransferHandler.handleContractTransfer(chainId, blockTime, tx, contractResult, tempBalanceManager);
                    resultList.add(contractResult);
                    break;
                default:
                    break;
            }
        }
        return resultList;
    }

}
