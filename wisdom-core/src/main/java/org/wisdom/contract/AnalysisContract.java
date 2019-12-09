package org.wisdom.contract;

import org.wisdom.ApiResult.APIResult;
import org.wisdom.db.AccountState;

import java.util.List;

public interface AnalysisContract {

    APIResult FormatCheck(List<AccountState> accountStateList);

    List<AccountState> update(List<AccountState> accountStateList);

    boolean RLPdeserialization(byte[] payload);

    byte[] RLPserialization();
}