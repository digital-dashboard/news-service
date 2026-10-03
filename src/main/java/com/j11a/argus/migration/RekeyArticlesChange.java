package com.j11a.argus.migration;

import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.exception.CustomChangeException;
import liquibase.exception.SetupException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;

public class RekeyArticlesChange implements CustomTaskChange {

    public RekeyArticlesChange() {
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
    }

    @Override
    public String getConfirmationMessage() {
        return "Re-key placeholder";
    }

    @Override
    public void setUp() throws SetupException {
    }

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {
    }

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
